# ModSync

A Minecraft mod that keeps a client's `mods/` folder in sync with what a server requires.
The player joins, sees a diff of what will change, accepts it, and the files are swapped in
after the game exits.

Multi-loader: Fabric, NeoForge and Forge, Minecraft 1.20.1 through 1.21.8.

> **Status: engine complete, not yet playable.** The synchronisation engine — manifest
> parsing, diffing, downloading, the crash-safe applier — is implemented and covered by 136
> tests. The only part wired into the game today is the `/modsync export` command. The join
> handshake that fetches a server's manifest and shows the player a diff is not built yet, so
> installing the mod on a server does not yet sync anyone. See [Roadmap](#roadmap).

## How it works

1. The client obtains the server's manifest — a JSON file listing every managed file with its
   size, hashes and download URLs.
2. It scans the game directory and diffs it against that manifest. Files are matched by hash,
   so a renamed-but-identical jar is not re-downloaded.
3. The player is shown what will be installed, replaced and moved aside, and accepts or
   declines. Declining a `require` file means not joining; declining an `optional` one is fine.
4. Accepted files download into a content-addressed cache, verified by hash as they stream.
5. The plan is written to a journal, Minecraft exits, and a small helper JVM applies the
   journal and swaps the files in. Windows will not let a running game's jars be replaced,
   which is why the swap happens after exit rather than in-process.

Nothing is ever deleted. Files the manifest displaces are **moved to quarantine**, so a bad
plan is always recoverable by hand.

### Profiles and the content cache

Downloads land in one content-addressed store (`modsync/cache/`) shared by every server, and
each pack gets a profile that hard-links out of it. A mod used by five servers is stored once,
and switching between servers is a relink rather than a re-download. Hard links fall back to
copies where the filesystem refuses them; symlinks are deliberately unused, because they need
Developer Mode or elevation on Windows.

### Crash safety

The journal has three verbs — `MKDIR`, `MOVE`, `LINK` — and none of them destroy anything.
Every operation is idempotent, so recovering from a crash mid-apply means replaying the
journal from the top. The test suite kills the applier between every pair of operations and
asserts the end state is identical each time.

## Manifest format

Format version 1. A bare JSON array is the pre-v1 sketch and is rejected by the parser.

```json
{
  "formatVersion": 1,
  "packId": "my-pack",
  "packName": "My Pack",
  "packVersion": "1.2.3",
  "generatedAt": "2026-09-04T11:30:00Z",
  "unlistedPolicy": "quarantine",
  "files": [
    {
      "id": "modrinth:AANobbMI",
      "label": "sodium.jar",
      "path": "mods/sodium.jar",
      "size": 1234567,
      "hashes": { "sha512": "...", "sha1": "..." },
      "urls": ["https://cdn.modrinth.com/..."],
      "policy": "require",
      "side": "both"
    }
  ]
}
```

| Field | Values | Meaning |
|---|---|---|
| `policy` | `require`, `recommend`, `optional`, `forbid` | Whether the player may decline the file |
| `side` | `client`, `server`, `both` | Where the file belongs |
| `urls` | list of mirrors | First URL that verifies wins |
| `unlistedPolicy` | `quarantine` (default), `keep` | What happens to files the manifest does not mention |

`quarantine` makes the manifest a whitelist, which is what makes updates free: when a pack
moves from `sodium-0.6.12.jar` to `0.6.13`, the old jar simply becomes unlisted, is moved
aside, and the new one is installed. No install-state bookkeeping, and two versions of one mod
can never coexist.

## Creating a manifest — `/modsync export`

Exports the files in a folder into a publishable manifest, with filenames, sizes and hashes
filled in for you.

```
/modsync export <folder> [packName] [packVersion]
/modsync export resolve <folder> [packName] [packVersion]
```

The plain form is offline and writes hashes only. Add `resolve` to look each file up on
Modrinth and CurseForge and fill in its download URL. Anything neither host recognises is
still exported, just without a `urls` entry, and the command tells you how many need one
before the manifest is publishable. A host being down degrades to "fewer URLs filled in",
never to a lost export.

`<folder>` must be one of `config`, `defaultconfigs`, `kubejs`, `mods`, `resourcepacks`,
`scripts` or `shaderpacks` — tab-completion lists them. Only `mods`, `resourcepacks` and
`shaderpacks` are looked up remotely; a config file has no project behind it, so querying it
would spend a request to learn nothing.

Exported entries start as `require`/`both`, except `resourcepacks` and `shaderpacks` which
start as `optional`/`client` — nobody should be blocked from joining because they declined a
shaderpack. Edit the exceptions before publishing.

Each run writes a new timestamped file to `.minecraft/modsync/exports/`, so an export you have
already edited is never overwritten. Files starting with `.` and files ending in `.disabled`
are skipped, as are symlinks.

The command is client-side, so it sees your shaderpacks and client-only mods. It is also
registered on dedicated servers for operators (permission level 2), where it warns that
client-only content is not installed there and will be missing from the export.

## Configuration

`.minecraft/modsync/modsync.json`, created with defaults on first use:

| Key | Default | Meaning |
|---|---|---|
| `alwaysKeep` | `mods/iris-*.jar`, `mods/sodium-extra-*.jar`, `shaderpacks/**` | Game-dir-relative globs ModSync must never quarantine or replace |
| `approvedHosts` | `[]` | Extra download hosts you trust, beyond the built-in allowlist |
| `parallelDownloads` | `4` | Concurrent downloads (clamped to 1–16) |
| `manifestOverrides` | `{}` | Per-server manifest URL overrides, keyed by `host:port` |
| `autoProbe` | `true` | Probe the server's endpoint automatically when joining |
| `curseForgeApiKey` | `""` | Personal key for `/modsync export resolve` |

`alwaysKeep` is the important one. Without it, the first sync to any server sweeps away the
client-side mods that no server can know about.

### CurseForge API key

CurseForge lookups need a personal API key from
[console.curseforge.com](https://console.curseforge.com):

```json
{
  "curseForgeApiKey": "your-key-here"
}
```

Without a key, `resolve` asks Modrinth only, and CurseForge-only files come back with no URL.

> **⚠️ Never ship `modsync/modsync.json` in a published modpack.** Most packs are built by
> zipping a working game directory, which is exactly how a private API key ends up on the
> internet. The key is yours, not the pack's. Exports are written to a separate folder
> (`modsync/exports/`) so you can hand those out without handing over your config. If you
> ever do leak a key, revoke it in the CurseForge console.

## Security model

A manifest arrives from a remote server, so it is treated as untrusted input throughout.

- **Where files may be written.** Only `mods`, `config`, `defaultconfigs`, `resourcepacks`,
  `shaderpacks`, `kubejs` and `scripts`. Traversal, absolute paths and writes through
  pre-existing symlinks are all rejected.
- **Where files may come from.** Downloads are restricted to `modrinth.com`, `curseforge.com`,
  `forgecdn.net`, `github.com` and `githubusercontent.com`, plus anything you add to
  `approvedHosts`.
- **Parser limits, enforced before allocation.** 10,000 files, 512-character paths, 16 URLs per
  entry, 2,048-character URLs, 16 GiB per file. The parser reads field by field off the JSON
  rather than reflecting into a class, so its errors name what an admin actually needs to fix.
- **Your keep rules beat the server's instructions.** `alwaysKeep`, plus built-in protection
  for ModSync's own jar and the loader, override any manifest. Losing ModSync mid-sync is not
  recoverable from inside the game.
- **Quarantine stays inside the roots the manifest touches.** A pack that only manages `mods/`
  cannot sweep your `shaderpacks/`.
- **Everything is verified by hash while it streams**, against SHA-512 (and SHA-1 where given).

## Building

Requires JDK 25 for the Gradle daemon — Loom refuses to set up Minecraft on a daemon older
than the version Minecraft itself requires, and the newest targets want 25. Individual nodes
still compile to Java 17 or 21 as appropriate.

```bash
./gradlew :core:test      # loader-independent engine tests
./gradlew buildAll        # all 8 loader/version nodes
./gradlew checkAll        # every node's checks, including verifyModMetadata
./gradlew collectJars     # gather every node's jar into build/libs
```

To work on a single node, switch the tree with
`./gradlew "Set active project to 1.21.1-fabric"`.

### Supported targets

| Minecraft | Fabric | NeoForge | Forge | Java |
|---|---|---|---|---|
| 1.20.1 | ✅ | — | ✅ 47.4.23 | 17 |
| 1.21.1 | ✅ | ✅ 21.1.249 (primary) | — | 21 |
| 1.21.4 | ✅ | ✅ 21.4.157 | — | 21 |
| 1.21.8 | ✅ | ✅ 21.8.54 | — | 21 |
| 26.2 | blocked | blocked | — | 25 |

Adding a target is one `match(...)` line in `settings.gradle.kts` plus a
`versions/<node>/gradle.properties`. 26.x is blocked on toolchain support for unobfuscated
Minecraft; [NOTES.md](NOTES.md) explains why, along with the rest of the build's reasoning.

## Project layout

```
core/     Pure Java engine — no Minecraft, no mappings, compiled and tested once
src/      Loader-facing layer — entrypoints and the /modsync command tree
versions/ Per-target Stonecutter nodes and their dependency versions
```

`:core` is where nearly everything lives:

| Package | Responsibility |
|---|---|
| `manifest` | Format v1 model, hand-written parser, limits |
| `security` | `PathSandbox` (where a manifest may write), `HostAllowlist` (where files may come from) |
| `hash` | SHA-512 + SHA-1 in one pass; Murmur2 for CurseForge lookups |
| `diff` | Scanner, hash cache, `Differ`, `SyncPlan`, `KeepRules` |
| `profile` | `ModSyncPaths`, `ContentCache`, `Profile`/`ProfileStore` |
| `apply` | `Journal`, `JournalOp`, `JournalApplier` |
| `net` | `Downloader` with mirrors, retries and streaming verification |
| `config` | Client settings |
| `export` | Folder scan, Modrinth/CurseForge lookup, manifest writer |

The applier has to run in a bare JVM after Minecraft has exited, where Gson is not on the
classpath — so the journal is tab-separated rather than JSON, and everything the applier
touches stays Gson-free.

## Roadmap

- [x] Manifest format v1, parser and security limits
- [x] Scanner, differ and keep rules
- [x] Content cache, profiles and hard-linking
- [x] Downloader with mirrors and streaming verification
- [x] Crash-safe journal and standalone applier
- [x] `/modsync export`, with Modrinth and CurseForge resolution
- [ ] Server-side manifest hosting and the join handshake
- [ ] The accept/decline diff screen
- [ ] Restart prompt and applier hand-off from the running game
- [ ] Windows validation of the post-exit file swap (only exercised on Linux so far)

## License

[LGPL-3.0-only](LICENSE).
