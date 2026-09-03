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

```json
[
    {
        "label": "TestMod",
        "desc": "This is a test mod that does nothing",
        "path": "mods/testmod.jar",
        "hash": "sha256:...",
        "url": "https://...",
        "required": true
    }
]
```

## Export client feature

Simple command to export current files in folders into a config.
This exports all files in the specified folder into a new config file in the mods config folder.
Every File will have the filename and hash filled by default.
The URL will be queried using the Modrinth and Curseforge APIs. if nothing matches, it is left empty with a notice in the export progress message about the missing url.
However, only files from the mods, resourcepacks and shaderpacks folders will be queried agains the APIs.
Querying additional files like config will not work anyways, so we can skip those.
```
/modsync export <folder>
```