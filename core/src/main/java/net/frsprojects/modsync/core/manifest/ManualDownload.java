package net.frsprojects.modsync.core.manifest;

/**
 * Where a person can download a file that ModSync is not allowed to fetch itself.
 *
 * <p>CurseForge lets authors opt out of third-party downloads, and many do. Such a file has no
 * URL ModSync may stream from, but the player can still get it from CurseForge's own site. The
 * client opens {@link #url} in the browser, watches the player's downloads folder, and takes
 * the file into the cache once one arrives whose SHA-512 matches the entry. Integrity rests on
 * the hash, never on where the bytes came from, so the browser is just another mirror.
 *
 * @param url an HTTPS page that starts the download, e.g.
 *     {@code https://www.curseforge.com/minecraft/mc-mods/jei/download/5846880}
 * @param fileName what the browser is expected to save the file as, shown to the player;
 *     null when unknown. A hint only: matching is by size and hash, because browsers rename
 *     duplicates to {@code foo (1).jar}.
 */
public record ManualDownload(String url, String fileName) {}
