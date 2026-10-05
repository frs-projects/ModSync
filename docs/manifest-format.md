# Manifest format v1

The complete reference for tools that generate ModSync manifests. Everything here is what the
client's parser (`ManifestCodec`) and sync logic actually enforce. A builder that follows this
page produces manifests the client accepts.

A manifest is one UTF-8 JSON object served over HTTPS. The client fetches it once per join,
and a dedicated server fetches it on startup and on `/modsync update`.

```json
{
  "formatVersion": 1,
  "packId": "my-pack",
  "packName": "My Pack",
  "packVersion": "1.2.3",
  "generatedAt": "2026-10-05T12:00:00Z",
  "unlistedPolicy": "quarantine",
  "files": [
    {
      "id": "modrinth:AANobbMI",
      "label": "Sodium",
      "path": "mods/sodium-neoforge-0.6.13.jar",
      "size": 1234567,
      "hashes": { "sha512": "…128 hex…", "sha1": "…40 hex…" },
      "urls": ["https://cdn.modrinth.com/data/AANobbMI/versions/…/sodium.jar"],
      "policy": "require",
      "side": "client"
    },
    {
      "id": "curseforge:238222",
      "label": "Just Enough Items",
      "path": "mods/jei-1.21.1-neoforge-19.21.0.247.jar",
      "size": 1456789,
      "hashes": { "sha512": "…128 hex…" },
      "manual": {
        "url": "https://www.curseforge.com/minecraft/mc-mods/jei/download/5846880",
        "fileName": "jei-1.21.1-neoforge-19.21.0.247.jar"
      },
      "policy": "require",
      "side": "both"
    }
  ]
}
```

## Envelope

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `formatVersion` | integer | **yes** | — | Must be `1`. A higher value is refused with "Update ModSync". |
| `packId` | string | no | client derives one from the server address | `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`. Becomes a directory name on every client, so it must be stable across releases: it keys the player's profile, quarantine and remembered optional choices. |
| `packName` | string | no | `packId`, else `"Unnamed pack"` | Shown in the sync screen title. |
| `packVersion` | string | no | `"0"` | Opaque, display only. |
| `generatedAt` | string | no | — | ISO-8601 instant (`2026-10-05T12:00:00Z`). If it does not parse, it is ignored rather than rejected. |
| `unlistedPolicy` | `"quarantine"` \| `"keep"` | no | `"quarantine"` | What happens to files the manifest does not mention, within the folders it manages (see [Managed folders](#managed-folders)). |
| `files` | array of [entries](#file-entries) | **yes** | — | At most 10,000. |

A bare top-level JSON array (the pre-v1 sketch) is refused. Unknown fields are ignored, so a
builder may add its own metadata.

## File entries

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `path` | string | **yes** | — | Where the file goes, relative to the game directory. See [Paths](#paths). |
| `hashes.sha512` | hex string | **yes**, except for `forbid` | — | 128 lowercase or uppercase hex characters. **The identity of the file.** Every download, cache lookup and hand-downloaded file is checked against it. |
| `hashes.sha1` | hex string | no | — | 40 hex characters. If given, it is checked too. |
| `size` | integer | strongly recommended | `-1` (unknown) | Bytes, 0 to 16 GiB. Used for progress display and to find hand-downloaded files quickly. For `manual` entries without a `size`, a downloaded file is only considered if its name equals `manual.fileName` exactly. |
| `urls` | array of strings | one of `urls` / `manual` | `[]` | Mirrors, tried in order; the first one whose bytes verify wins. At most 16, each at most 2,048 characters. Every URL's host must be [allowed](#download-hosts). |
| `url` | string | no | — | Legacy single-URL form. It is put in front of `urls`, and duplicates are dropped. |
| `manual` | object | one of `urls` / `manual` | — | A page where the player can download the file in their browser. See [Manual downloads](#manual-downloads). |
| `policy` | `"require"` \| `"recommend"` \| `"optional"` \| `"forbid"` | no | `"require"` | See [Policies](#policies). |
| `side` | `"client"` \| `"server"` \| `"both"` | no | `"both"` | Clients take `client` and `both`; a syncing dedicated server takes `server` and `both`. |
| `id` | string | no | `path` | Stable identity across versions, e.g. `modrinth:AANobbMI` or `curseforge:238222`. It keys remembered optional choices, so keep it constant when the file updates. |
| `label` | string | no | file name from `path` | Display name. |
| `desc` | string | no | — | Shown next to recommended/optional files on the choice page. |
| `group` | string | no | — | UI grouping hint, e.g. `"Performance"`. |
| `loaders` | string or array of strings | no | any loader | Case-insensitive, e.g. `["neoforge", "fabric"]`. |
| `mcVersions` | string or array of strings | no | any version | Exact Minecraft versions, e.g. `["1.21.1"]`. |
| `defaultEnabled` | boolean | no | `true`, or `false` for `optional` | Whether the choice page pre-ticks a recommended or optional file. |
| `required` | boolean | no | — | Legacy. Read only if `policy` is absent: `true` means `require`, `false` means `optional`. |

Text fields are trimmed and capped at 4,096 characters.

### Where a file comes from

An entry that is not `forbid` needs a source the client can use:

1. If the file is already in the player's content cache (from any pack), no source is used.
2. Otherwise, if `urls` is non-empty, the client downloads from it. `manual` is ignored.
3. Otherwise, if `manual` is present, the player downloads the file in their browser.
4. Otherwise the entry cannot be satisfied. A `require` entry then stops the player from
   syncing at all, and a non-mandatory one is skipped.

So **only put `manual` on an entry with no `urls`.** If a file has a usable URL, `manual` is
never used.

### Policies

| Policy | Client | Dedicated server |
|---|---|---|
| `require` | Always installed, never asked | Installed |
| `recommend` | Asked on the choice page, ticked by default | Installed |
| `optional` | Asked on the choice page, unticked by default | Skipped |
| `forbid` | Moved to quarantine if present | Moved to quarantine if present |

A `forbid` entry may omit `hashes` and then matches by `path` alone. If it includes hashes,
they must be valid.

### Paths

- Forward slashes; backslashes are converted. At most 512 characters.
- Relative: no leading `/`, no drive letter, no `.` or `..` segments, no trailing `/`, no
  leading or trailing whitespace.
- The first segment must be one of `mods`, `config`, `defaultconfigs`, `resourcepacks`,
  `shaderpacks`, `kubejs`, `scripts`. One path outside these rejects **the whole manifest**.
- Give every entry its own `path`. The parser does not check this, but if two entries share
  a path, the sync plan has two actions for one file and the result is undefined.

### Managed folders

With `unlistedPolicy: "quarantine"` the manifest acts as a whitelist **within the top-level
folders its entries use**. A manifest that only lists files under `mods/` never touches
`shaderpacks/`. Unlisted files in a managed folder are moved to `modsync/quarantine/<packId>/`,
never deleted, unless they match the player's `alwaysKeep` globs.

This is how updates work: list `sodium-0.6.13.jar` instead of `sodium-0.6.12.jar`, and the old
jar becomes unlisted and is moved aside.

### Download hosts

The client downloads only over HTTPS, and only from:

- `modrinth.com`, `curseforge.com`, `forgecdn.net`, `github.com`, `githubusercontent.com`,
  including their subdomains;
- the host the manifest itself is served from, if the manifest URL is `https://`. This exact
  host only, not its subdomains;
- hosts the player or server admin added to `approvedHosts`.

The same rule decides which `manual.url` pages the client is willing to open.

## Manual downloads

Some CurseForge authors disable third-party downloads. The CurseForge API then returns
`"downloadUrl": null` for their files (and `"allowModDistribution": false` on the mod), and
nothing else may serve the jar for them. A `manual` block covers this case. The client
opens the file's page on CurseForge in the player's browser. The player downloads the file
there, and ModSync picks it up from their downloads folder by hash.

```json
"manual": {
  "url": "https://www.curseforge.com/minecraft/mc-mods/jei/download/5846880",
  "fileName": "jei-1.21.1-neoforge-19.21.0.247.jar"
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `url` | string | **yes** | `https://` only, at most 2,048 characters. It must be on an [allowed host](#download-hosts), or the client shows the file as refused instead of opening it. |
| `fileName` | string | no | The name the browser is expected to save the file as. It is shown to the player, and is the only way a file is found when the entry has no `size`. No `/` or `\`. |

`manual` must be an object if present. A string, a missing `url` or a non-HTTPS `url` rejects
the manifest.

### Building the CurseForge URL

Use the page that starts the download of that exact file:

```
{project websiteUrl}/download/{fileId}
```

With the [CurseForge API](https://docs.curseforge.com/rest-api/) (`x-api-key` header):

1. Find the file. Use `GET /v1/mods/{modId}/files/{fileId}` if you know it, or
   `POST /v1/fingerprints` with the file's Murmur2 fingerprint. Either way you get the
   file's `id`, `modId`, `fileName`, `fileLength` and `downloadUrl`.
2. If `downloadUrl` is not null, put it in `urls` and you are done.
3. Otherwise fetch the project with `GET /v1/mods/{modId}` (or `POST /v1/mods` with
   `{"modIds": [...]}` for many at once) and read `data.links.websiteUrl`, e.g.
   `https://www.curseforge.com/minecraft/mc-mods/jei`.
4. Set `manual.url` to `websiteUrl` without any trailing slash, plus `/download/` and the
   file's `id`. Set `manual.fileName` to `fileName` and `size` to `fileLength`.

`/modsync export resolve` does exactly this when a `curseForgeApiKey` is configured.

### The hash still has to be real

`hashes.sha512` is required for a `manual` entry like any other. The CurseForge API gives only
SHA-1 and MD5, so the builder has to hash the actual file. Get the file through a normal
download and hash it, and **do not host it**: hosting it is the redistribution the author
opted out of. It can come from the admin's own browser download, an upload into the builder,
or the server's `mods/` folder. You can check that you have the right file against the API's
`fileLength` and its SHA-1 (`hashes` with `algo: 1`). Put that SHA-1 in `hashes.sha1` as well
if you like; the client then checks both.

If the hash is wrong, the player's download never matches. They see the file as still
waiting and cannot sync.

### What the player sees

The client screen opens after the player clicks **Sync**:

- It lists each file to download by hand. Clicking a file, or **Open next**, opens its
  `manual.url` in the browser.
- It checks the top level of the downloads folder every second. Unfinished downloads
  (`.part`, `.crdownload`, …) are skipped, and only files whose `size` matches are hashed.
  The browser renaming a file (`jei (1).jar`) does not matter.
- The downloads folder is the platform default (on Linux, the XDG `user-dirs.dirs` entry),
  plus any `downloadFolders` in `modsync/modsync.json`, plus `modsync/import/`. **Add
  folder…** picks another one and remembers it. Files can also be dragged onto the game
  window.
- A matching file is **copied** into the content cache. The original stays where it is.
- When every file has arrived, the normal sync continues on its own. **Skip optional** drops
  the `recommend` and `optional` files that are still waiting.

A file downloaded by hand once is cached, so it serves every pack that lists the same SHA-512
and is never asked for again.

### Dedicated servers

A server has no browser, so it looks only in `modsync/import/`, which it creates. If a
`require` file is missing, the update is **blocked**. The log then lists each missing file
with its `manual.url`, and the admin drops the files into `modsync/import/` and runs
`/modsync update`. A missing `recommend` file is skipped, so the rest of the update still
goes ahead.

### Older clients

`manual` was added within format version 1, because the change is additive. A ModSync build
older than this feature ignores the field and treats the entry as having no source: a
`require` entry blocks the sync with "no download URL and not in the cache", exactly as
before.

## Checklist for builders

- [ ] `formatVersion: 1`, a stable `packId`, and a `files` array.
- [ ] Every non-`forbid` entry has a correct `sha512` of the exact bytes, and a `size`.
- [ ] Every non-`forbid` entry has `urls` on an allowed host, or a `manual` block, unless it
      is acceptable for that entry to be unobtainable.
- [ ] `manual` only on entries without `urls`, and only pointing at the file's CurseForge
      download page, never at a copy you host.
- [ ] Paths are relative, start in an allowed folder, and are unique.
- [ ] `id` stays the same when a file updates to a new version.
- [ ] Served over HTTPS. Round-trip check: the client's parser must accept the file
      (`ManifestCodec.parse`).
