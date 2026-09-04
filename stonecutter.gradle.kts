plugins {
    id("dev.kikugie.stonecutter")
    id("dev.architectury.loom") version "1.17.491" apply false
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

// Cross-version aggregate tasks. Stonecutter 0.9 hands back a node -> task map
// rather than creating a `chiseled*` task itself, so the fan-out is wired here.
val buildTasks = stonecutter.tasks.named("build")
val checkTasks = stonecutter.tasks.named("check")

tasks.register("buildAll") {
    group = "modsync"
    description = "Builds every Minecraft version x loader node in the matrix."
    dependsOn(buildTasks.map { it.values })
}

tasks.register("checkAll") {
    group = "modsync"
    description = "Runs every node's checks, including mod metadata verification."
    dependsOn(checkTasks.map { it.values })
}

// One place to find every jar the matrix produced.
val collectJars by tasks.registering(Copy::class) {
    group = "modsync"
    description = "Copies every node's remapped jar into the root build/libs."
    into(layout.buildDirectory.dir("libs"))
    stonecutter.versions.forEach { node ->
        from(project(":${node.project}").layout.buildDirectory.dir("libs")) {
            include("*.jar")
            exclude("*-sources.jar", "*-dev.jar", "*-dev-shadow.jar")
        }
    }
    dependsOn(buildTasks.map { it.values })
}
