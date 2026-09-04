import java.util.zip.ZipFile

// Shared configuration for every Stonecutter node, regardless of loader.
// Deliberately contains nothing that touches the Loom API, so build-logic does
// not need Loom on its classpath.

plugins {
    id("java")
}

fun prop(key: String): String = rootProject.property(key) as String
fun propOrNull(key: String): String? = findProperty(key) as String?

group = prop("mod.group")
// e.g. modsync-0.1.0+1.21.1-neoforge.jar -- the node is the build metadata, so a
// user can tell at a glance which of ten jars they downloaded.
version = "${prop("mod.version")}+${project.name}"
base.archivesName = prop("mod.id")

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
    maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
    maven("https://maven.architectury.dev/") { name = "Architectury" }
    maven("https://api.modrinth.com/maven") { name = "Modrinth" }
}

// Each node declares its own Java level; 1.20.1 is 17, 1.21.x is 21, 26.x is 25.
val nodeJava = (propOrNull("java.version") ?: "21").toInt()

java {
    toolchain.languageVersion = JavaLanguageVersion.of(nodeJava)
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = nodeJava
}

dependencies {
    // The pure-Java engine. No Minecraft types, so it needs no remapping; its
    // classes are folded into the platform jar below.
    "implementation"(project(":core"))
    "compileOnly"("org.jetbrains:annotations:26.0.2")
}

tasks.named<Jar>("jar") {
    manifest.attributes(
        "Specification-Title" to prop("mod.name"),
        "Implementation-Version" to prop("mod.version"),
    )
}

val loader: String = project.name.substringAfterLast('-')

val expectedMetadata: String = when (loader) {
    "fabric" -> "fabric.mod.json"
    "neoforge" -> "META-INF/neoforge.mods.toml"
    "forge" -> "META-INF/mods.toml"
    else -> error("Cannot infer loader for node '${project.name}'")
}

// Mod metadata is templated from gradle.properties so version/id/deps live in
// exactly one place per node.
tasks.named<ProcessResources>("processResources") {
    // :core's classes are folded in here rather than in the jar task, because this directory
    // is also what the dev runs load the mod from. FML puts a mod in its own module layer and
    // unions only the node's own classes/resources dirs into it, so with :core merely on the
    // runtime classpath anything touching it dies with NoClassDefFoundError under
    // runClient/runServer. Shipped jars were never affected, which is why this stayed hidden
    // until the first :core class was loaded at runtime instead of inlined as a constant.
    // (Fabric's Knot classloader is flat and never needed this.)
    from(project(":core").layout.buildDirectory.dir("classes/java/main")) {
        exclude("**/*.kotlin_metadata")
    }
    dependsOn(":core:classes")

    val tokens = mapOf(
        "id" to prop("mod.id"),
        "name" to prop("mod.name"),
        "version" to prop("mod.version"),
        "group" to prop("mod.group"),
        "license" to prop("mod.license"),
        "description" to prop("mod.description"),
        "authors" to prop("mod.authors"),
        "source" to prop("mod.source"),
        "java" to nodeJava.toString(),
        "minecraft" to (propOrNull("deps.minecraft") ?: ""),
        "pack_format" to (propOrNull("deps.pack_format") ?: "15"),
        "minecraft_range_fabric" to (propOrNull("deps.minecraft.range.fabric") ?: ""),
        "minecraft_range_forgelike" to (propOrNull("deps.minecraft.range.forgelike") ?: ""),
        "fabric_loader" to (propOrNull("deps.fabric_loader") ?: ""),
        "fabric_api" to (propOrNull("deps.fabric_api") ?: ""),
        "neoforge" to (propOrNull("deps.neoforge") ?: ""),
        "neoforge_range" to (propOrNull("deps.neoforge.range") ?: ""),
        // The javafml language-provider version, which is NOT the NeoForge version: NeoForge
        // 21.1/21.4/21.8 ship javafml 4/6/9. Putting the loader range here makes FML refuse
        // the mod outright with "needs language provider javafml:21.1 or above".
        "javafml_range" to (propOrNull("deps.javafml.range") ?: "[1,)"),
        "forge" to (propOrNull("deps.forge") ?: ""),
        "forge_range" to (propOrNull("deps.forge.range") ?: ""),
    )
    inputs.properties(tokens)
    val allMetadata = listOf("fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml")
    // Only this node's loader metadata is templated; the others would fail to
    // expand (their tokens are empty here) and would be dead weight in the jar.
    exclude(allMetadata.filter { it != expectedMetadata })
    // pack.mcmeta is loader-agnostic but mandatory: without it Forge cannot build
    // a ResourcePackInfo for the mod file and aborts the client with a full-screen
    // error, while Fabric/NeoForge only log "Missing metadata in pack".
    filesMatching(listOf(expectedMetadata, "pack.mcmeta")) { expand(tokens) }
}


// Guard against the failure mode that bit the reference project: a jar that
// builds fine but is missing its loader metadata, so it silently loads nothing.
val verifyModMetadata by tasks.registering {
    description = "Asserts the built jar actually contains its loader metadata."
    group = "verification"
    // remapJar is Loom's real output; fall back to jar when Loom is absent.
    val jarTask = tasks.findByName("remapJar") ?: tasks.named("jar").get()
    dependsOn(jarTask)
    val archive = (jarTask as AbstractArchiveTask).archiveFile
    inputs.file(archive)
    doLast {
        val f = archive.get().asFile
        ZipFile(f).use { zip ->
            requireNotNull(zip.getEntry(expectedMetadata)) {
                "$f is missing $expectedMetadata — it would load as an empty mod. " +
                    "Check processResources for node '${project.name}'."
            }
            requireNotNull(zip.getEntry("pack.mcmeta")) {
                "$f is missing pack.mcmeta — Forge would reject it with " +
                    "'failed to load a valid ResourcePackInfo'. " +
                    "Check processResources for node '${project.name}'."
            }
        }
        logger.lifecycle("verifyModMetadata: ${f.name} contains $expectedMetadata + pack.mcmeta")
    }
}

tasks.named("check") { dependsOn(verifyModMetadata) }
