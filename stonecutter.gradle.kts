plugins {
    id("dev.kikugie.stonecutter")
    id("frs.controller")
}

val modVersion = property("mod.version") as String
val modId = property("mod.id") as String

stonecutter active file(".sc_active_version")

stonecutter parameters {
    // Exposes `fabric` / `neoforge` / `forge` as Stonecutter constants, so source
    // can branch on loader with `//? if fabric {`.
    constants.match(current.project.substringAfterLast('-'), "fabric", "neoforge", "forge")

    swaps["mod_version"] = "\"$modVersion\";"
    swaps["mod_id"] = "\"$modId\";"
    swaps["minecraft"] = "\"${current.version}\";"
}
