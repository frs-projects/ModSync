# ModSync

Simple mod that allows a Minecraft client to Synchronize their Mods folder with what the Server they are joining requires.

## Workflow:

- Server sends required and Optional file Lists to the Client
- Client checks files using the filename and hash.
- Client prompts the user with a diff
- User accepts or declines (Required decline, cant join. optional decline can)
- Client then continues to download the files from the accepted list(s)
- Minecraft then quits (prompt user to restart) to load the new mods.

## File List format

Manifest format v1. A bare JSON array is the pre-v1 sketch and is rejected by the parser.

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

`policy` is one of `require`, `recommend`, `optional`, `forbid`. `side` is `client`, `server`
or `both`. `urls` is a mirror list; the first one that verifies wins.

## Export client feature

Exports the files in a folder into a manifest you can publish, with the filename, size and
hashes filled in for you.

```
/modsync export <folder> [packName] [packVersion]
/modsync export resolve <folder> [packName] [packVersion]
```

The plain form is offline and writes hashes only. Add `resolve` to look each file up on
Modrinth and CurseForge and fill in its download URL. Anything neither host recognises is
still exported, just without a `urls` entry, and the command tells you how many need one
before the manifest is publishable.

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
client-only content is not installed there and will be missing.

### CurseForge API key

CurseForge lookups need a personal API key from
[console.curseforge.com](https://console.curseforge.com). Put it in
`.minecraft/modsync/modsync.json`:

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
