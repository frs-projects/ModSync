# Syncing against taczbg-control manifests

Handoff for the next piece of work on the mod: the roadmap items "server-side manifest hosting
and the join handshake" and "the accept/decline diff screen", built against the manifests the
TACZ:BG control panel (`../taczbg-control`, module `modsync`) now publishes. The panel side is
done and tested; everything below is mod work.

Panel docs: `taczbg-control/docs/modules/modsync.md`. Panel code worth reading for the
contract: `modules/modsync/src/Services/ManifestBuilder.php` (what it emits) and
`modules/modsync/src/Services/ManifestRules.php` (the copy of this mod's path and host rules).

## What the panel serves

```
GET https://<panel-host>/p/modsync/<packId>.json
```

- No authentication. `200` with `Content-Type: application/json`, a strong `ETag` (the
  SHA-256 of the file) and `X-Modsync-Build: <n>` (the panel's build number). Send
  `If-None-Match` and the panel answers `304` when nothing changed. Unknown pack, or no build
  yet: `404`.
- Rate-limited to 60 requests per minute per IP (shared by every `/p/**` page). One fetch per
  join is fine; never poll in a loop.
- The manifest only changes when a panel user runs a build, never on an edit. The newest build
  is the live one.

Example (field order as emitted, pretty-printed, trailing newline):

```json
{
    "formatVersion": 1,
    "packId": "main",
    "packName": "TACZ:BG",
    "packVersion": "2.1.0",
    "unlistedPolicy": "quarantine",
    "files": [
        {
            "label": "Old mod",
            "path": "mods/old.jar",
            "policy": "forbid",
            "side": "both"
        },
        {
            "id": "modrinth:AANobbMI",
            "label": "Sodium",
            "desc": "…",
            "path": "mods/sodium.jar",
            "size": 1234567,
            "hashes": { "sha512": "…", "sha1": "…" },
            "urls": ["https://cdn.modrinth.com/data/AANobbMI/versions/…/sodium.jar"],
            "policy": "require",
            "side": "both"
        }
    ]
}
```

What to rely on:

- Exactly the v1 format `ManifestCodec` parses. Null fields are left out, not sent as `null`.
- `packId` matches `[a-z0-9][a-z0-9._-]{0,63}` (lower case only).
- Every non-`forbid` entry has `sha512` (and usually `sha1`), `size` and at least one URL.
  `forbid` entries may have only `label`, `path`, `policy` and `side`. The panel refuses to
  build anything else, so a parse error against the panel is a bug on one side.
- `id` is `modrinth:<projectId>` or `curseforge:<modId>`, or absent (uploads, URLs).
- No `generatedAt`, `loaders`, `mcVersions`, `group` or `defaultEnabled`: a pack has one loader
  and game version, set on the panel. Keep tolerating them, since other manifests may send them.
- Paths are already checked against `PathSandbox`'s roots and segment rules on the panel. Keep
  checking: the manifest is still untrusted input.

### Download hosts

URLs point at `cdn.modrinth.com`, `edge.forgecdn.net`, GitHub release hosts, or **the panel
itself** for files a staff member uploaded:

```
https://<panel-host>/p/modsync/files/<fileId>/<first 16 hex of sha512>/<fileName>
```

The panel host is in neither `HostAllowlist.DEFAULT_HOSTS` nor (usually) the game server's
host, so today these downloads are refused unless the player adds the panel to
`approvedHosts`. The panel warns about it on every build.

## Work to do in the mod

### 1. Trust the host the manifest came from

Add `HostAllowlist.plusManifestHost(String manifestUrl)`: the manifest's own host, exact match,
**HTTPS only**. Keep it separate from `plusServer` (which also allows plain HTTP), because the
panel is always behind TLS. Reasoning: a manifest pins every file by SHA-512 and comes from a
URL the player's server chose, so its host can serve nothing the manifest could not already
point at elsewhere. Wire it wherever the `Downloader`'s allowlist is built for a sync. Test it
next to `HostAllowlistTest`: allowed over https, refused over http, and not a suffix match
(`evil.panel.example` must not pass for `panel.example`).

### 2. Find the manifest URL when joining

The panel and the game servers are different hosts, so "probe the server's HTTP endpoint" (the
`autoProbe` idea in `ModSyncConfig`) cannot find the panel. The server has to tell the client.
Proposal, to confirm with the user before building:

- **Server side:** a small server config (e.g. `config/modsync-server.json`) with
  `manifestUrl`. Optional: without it the server sends nothing and nothing happens.
- **Delivery:** a custom payload the server sends during login/configuration, before the
  player is in the world, so a sync can happen before they load in with the wrong mods.
  1.20.2+ (NeoForge 21.x, Fabric) has the configuration phase. Forge 1.20.1 has login-phase
  packets through `SimpleChannel`. Fabric 1.20.1 has `ServerLoginNetworking`. These differ per
  loader and version, so expect Stonecutter predicates, unlike the command layer.
- **Client side:** `manifestOverrides["host:port"]` wins over what the server sends (that is
  its purpose, and it is the stopgap the panel docs tell players to use today). The server
  only sends a URL. The client fetches it with its own HTTP client, never by trusting payload
  contents.
- A client without ModSync must still connect: make the payload optional or ignorable.
- Vanilla/unmodded servers: no payload, no sync, no error.

### 3. Fetch, diff, ask, apply

- Fetch with `If-None-Match` from the last fetch of that URL. Store the ETag and the parsed
  manifest per server (e.g. in the profile). On `304`, reuse the stored manifest; on `404` or a
  network error, let the player join with what they have and show a short message. Never block
  the join on the panel being down.
- Parse with `ManifestCodec.parse`, diff with `Differ`, and show the diff screen (installs,
  replacements, quarantines; `require` cannot be declined, `optional` can). Show `packName`,
  `packVersion`, and each entry's `label`/`desc`.
- Accept → `Downloader.fetchAll` → journal → restart prompt → `JournalApplier --wait-for-pid`,
  as the README's roadmap already lays out.

### 4. Test against a real panel manifest

From `taczbg-control`, a manifest can be produced without the UI:

```sh
php artisan tinker --execute 'echo json_encode(app(Modules\Modsync\Services\ManifestBuilder::class)->manifest(Modules\Modsync\Models\ModsyncPack::with("files")->first()), JSON_PRETTY_PRINT|JSON_UNESCAPED_SLASHES);'
```

Commit one as a fixture under `core/src/test/resources` and add a `ManifestCodecTest` case that
parses it. That locks the contract from the mod's side, just as the panel's
`ManifestPublishTest` locks it from the panel's side. If either side changes the format, bump
`formatVersion` on both.

## Not in scope here

- Pushing jars to the game servers' own `mods/` folders. That would be the panel's agent
  `deploy`, not this mod.
- Panel changes. The panel needs none for the steps above. If step 2 lands on a different URL
  scheme, the panel route is in `modules/modsync/http/player.php`.
