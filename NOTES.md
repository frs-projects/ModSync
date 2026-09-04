# Build notes

## Matrix

| Minecraft | Fabric | NeoForge | Forge | Java |
|---|---|---|---|---|
| 1.20.1 | ✅ | — | ✅ 47.4.23 | 17 |
| 1.21.1 | ✅ | ✅ 21.1.249 (primary) | — | 21 |
| 1.21.4 | ✅ | ✅ 21.4.157 | — | 21 |
| 1.21.8 | ✅ | ✅ 21.8.54 | — | 21 |
| 26.2 | blocked | blocked | — | 25 |

Adding a target is one `match(...)` line in `settings.gradle.kts` plus a
`versions/<node>/gradle.properties` holding that node's dependency versions.

### Why NeoForge 1.20.1 is absent

NeoForge for 1.20.1 is published as `net.neoforged:forge:1.20.1-47.1.x`, the pre-fork
artifact, and is effectively Forge 47. The `1.20.1-forge` node covers that ground.

### Why 26.2 is blocked

Minecraft ships **unobfuscated** from the 1.21.11 / 26.x line onward. Mojang consequently
stopped publishing mapping files:

```
1.21.8   downloads=[client, client_mappings, server, server_mappings]
26.1.2   downloads=[client, server]
26.2     downloads=[client, server]
```

Yarn stopped at `1.21.11+build.6` for the same reason. Architectury Loom 1.17.491 (the
newest release; there is no 1.18 line) still requires a mapping set and fails with
`Failed to find official mojang mappings for 26.2`. Empty layered mappings fail differently
(`srcNamespace is null`).

Fabric Loom is further along: `net.fabricmc:fabric-loom` is at `1.18.0-alpha.x`, and the
Fabric maven has begun carrying `fabric-loom-no-remap` / `fabric-loom-remap` directories
(no releases published there yet). Whether 1.18-alpha actually handles 26.2 has **not been
verified here** — checking it means switching toolchains.

So 26.x needs either a future Architectury Loom release, or a move to Fabric Loom +
NeoForge's ModDevGradle (`net.neoforged:moddev-gradle`, currently 2.0.146) for the whole
build. The per-node `deps.mappings` property already exists to select the mapping source,
so the seam is in place either way.

## Toolchain

- **Gradle 9.7**, daemon pinned to **Java 25** via `gradle/gradle-daemon-jvm.properties`.
  Loom refuses to set up Minecraft when the daemon JVM is older than the version Minecraft
  requires, and 26.x wants Java 25 — so the daemon must be the newest in the matrix even
  though most nodes compile to older bytecode.
- **Per-node `options.release`** (17 / 21 / 25) via `java.version` in each node's properties.
- **`:core` is a plain `java-library` with no Minecraft dependency**, compiled to Java 17
  because 1.20.1 is the floor. It is compiled and tested exactly once, not per node.
- Gson is `compileOnly` in `:core`: Minecraft bundles it on every loader, so shading it would
  duplicate a class that is already on the classpath. The standalone applier must therefore
  avoid Gson entirely — its journal format is line-based, not JSON.

## Useful tasks

| Task | What it does |
|---|---|
| `./gradlew buildAll` | Builds all 8 nodes |
| `./gradlew checkAll` | Runs every node's checks, including `verifyModMetadata` |
| `./gradlew collectJars` | Copies every node's jar into `build/libs` |
| `./gradlew :core:test` | Runs the loader-independent test suite |
| `./gradlew "Set active project to 1.21.1-fabric"` | Switches the working tree to another node |

`verifyModMetadata` opens each built jar and asserts its loader metadata file is present at
the archive root. This guards the failure mode seen in `PlayerDataSyncReloaded`: a jar that
builds cleanly, uploads fine, and loads as an empty mod.

## :core architecture

`:core` has no Minecraft dependency, no mappings and no preprocessing. It compiles and tests
once, on Java 17, and is folded into each platform jar.

| Package | Responsibility |
|---|---|
| `manifest` | Format v1 model, hand-written parser, limits, legacy-field tolerance |
| `security` | `PathSandbox` (where a manifest may write), `HostAllowlist` (where files may come from) |
| `hash` | SHA-512 + SHA-1 in one pass; Murmur2 for CurseForge lookups only |
| `diff` | Scanner, hash cache, `Differ`, `SyncPlan`, `KeepRules` |
| `profile` | `ModSyncPaths`, `ContentCache`, `Profile`/`ProfileStore` |
| `apply` | `Journal`, `JournalOp`, `JournalApplier` |
| `net` | `Downloader` with mirrors, retries and streaming hash verification |
| `config` | Client settings, including the alwaysKeep globs |
| `export` | Folder scan, Modrinth/CurseForge lookup, manifest writer for `/modsync export` |

### Design decisions worth knowing

**The manifest parser does not use reflection.** It reads field by field off a `JsonObject`.
A manifest arrives from a remote server, so the parser is the trust boundary: it has to
produce errors an admin can act on, and enforce size limits before allocating.

**The journal is tab-separated, not JSON.** `JournalApplier` runs in a bare JVM after
Minecraft has exited. Gson is only available because Minecraft bundles it, so the applier and
everything it touches stays Gson-free. Verified by running the applier with only `core-*.jar`
on the classpath.

**Only three journal verbs, none destructive.** `MKDIR`, `MOVE`, `LINK`. There is no delete:
displaced files go to quarantine, so a wrong plan is always recoverable. Quarantining twice
does not clobber the first file.

**Crash recovery is replay.** Every operation is idempotent, so recovery is re-running the
whole journal from the top. `JournalApplierTest` drives a kill between every pair of
operations and asserts the end state is identical each time.

**The manifest is a whitelist, which makes updates free.** `sodium-0.6.12.jar` simply becomes
unlisted when the manifest moves to `0.6.13`, so it is quarantined and the new jar installed.
No install-state bookkeeping, and two versions of one mod can never coexist.

**Quarantine is confined to roots the manifest touches.** A pack that only manages `mods/`
cannot sweep the player's `shaderpacks/`.

**A user keep rule beats a manifest instruction.** `alwaysKeep` and the built-in protection
for ModSync's own jar and the loader win over a server's opinion — losing ModSync mid-sync is
unrecoverable from inside the game.

**Hard link, then fall back to copy.** Confirmed at the filesystem level (same inode, 2
links). Symlinks are deliberately unused: they need Developer Mode or elevation on Windows.

**Export writes the same format the client reads.** `/modsync export` emits manifest v1, so an
export can be published without a conversion step — and so round-tripping our own output back
through `ManifestCodec.parse` is a real test of the exporter. That test is what catches a
`packId` outside the parser's charset, or an entry that lost its hash.

**Export does not reuse `LocalScanner`.** That scanner ends every pass with
`stateCache.retainOnly(...)`, so scanning one folder for an export would evict the hash cache
for every root it did not visit and force a full rehash on the next join. Export is rare and
explicit; paying for its own hashes is cheaper than corrupting the cache the sync path lives on.

**URL lookups are opt-in.** `/modsync export mods` is offline; `/modsync export resolve mods`
queries the hosts. A command that silently makes outbound requests is a surprise, and an
air-gapped or rate-limited export is a normal thing to want. A host being down degrades to
"fewer URLs filled in", never to a lost export.

**Client feedback bypasses the command source.** On Forge 1.20.1 a client command's source is
the `LocalPlayer`, whose `acceptsSuccess()` reads the `sendCommandFeedback` gamerule — so
progress would be silently swallowed on any server that turns that rule off. `ClientChat`
writes to the chat overlay directly, which also means no source is captured across threads and
a disconnect mid-export cannot leave the worker holding a dead `ClientPacketListener`.

**One Brigadier tree, four registration paths.** The paths hand back three incompatible source
types, so `ModSyncCommand` is generic over `S` and takes the two things that differ — who may
run it, and how to talk back — as arguments. It references only Brigadier and loader-neutral
Minecraft classes: `Minecraft` is client-only and would abort a dedicated server the moment
that class loaded, so every client reference is confined to `ClientChat` and reached only
behind a dist check.

**The server-side command only registers on a dedicated server.** Gated on
`CommandSelection.DEDICATED`, so it can never collide with the client command in singleplayer.
It warns on use: a server has no shaderpacks and no client-only mods, so its export is missing
exactly the content the feature exists to capture.

### Command API stability across the matrix

Verified by disassembling the cached jars, not by assumption — the whole command layer needs
**no version predicates**, only loader gating:

- `ClientCommandRegistrationCallback` and `CommandRegistrationCallback` are byte-identical
  across fabric-command-api-v2 `2.2.14` / `2.2.28` / `2.2.41` / `2.3.1`.
- `CommandSourceStack.sendSuccess(Supplier<Component>, boolean)` is the same on 1.20.1 and
  1.21.8; the `Component` -> `Supplier` change landed before 1.20.1.
- `RegisterCommandsEvent` / `RegisterClientCommandsEvent` have the same shape on Forge 47,
  NeoForge 21.1 and 21.8 — only the package differs. Both are **game-bus** events, so the
  existing no-arg `@Mod` constructor is enough to subscribe.

Two traps that would have forced a predicate, both avoided: `MinecraftServer.getServerDirectory()`
returns `File` on 1.20.1 and `Path` from 1.21.1 (the loader APIs `FabricLoader.getGameDir()` /
`FMLPaths.GAMEDIR` are used instead), and `@EventBusSubscriber(bus = ...)` is gone in the FML
that ships with NeoForge 21.8.

Forge's eventbus 6 has no `addListener(Class, Consumer)` overload and cannot infer an event
type from an untyped lambda, so its listeners are method references. NeoForge's bus 8 has the
`Class` overload on every version in the matrix.

**Loader-gated files carry only `//` comments.** Stonecutter comments an inactive file out by
wrapping it in `/* */`, and a `*/` inside a Javadoc block would close that early.

### Windows file locking

A running Minecraft holds every jar in `mods/` open, and Windows will not let an open file be
deleted, renamed or overwritten. Fabric's `preLaunch` is no help — mod discovery has already
opened the jars by then. So the swap happens from a detached helper that outlives the game:

```
java -cp modsync.jar net.frsprojects.modsync.core.apply.JournalApplier <gameDir> \
     --wait-for-pid <minecraft pid> [--timeout-seconds 120]
```

If the game outlives the timeout the journal is left in place and replayed at next launch.
**This path has only been exercised on Linux so far** — the plan calls for Windows validation,
and that is where the design is most likely to break.
