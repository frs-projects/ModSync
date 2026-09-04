plugins {
    `java-library`
}

group = "${rootProject.property("mod.group")}.core"
version = rootProject.property("mod.version") as String

repositories { mavenCentral() }

val coreJava = (rootProject.property("core.java") as String).toInt()

java {
    toolchain.languageVersion = JavaLanguageVersion.of(coreJava)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // The lowest Java in the matrix (1.20.1 runs on 17), so :core must stay 17.
    options.release = coreJava
}

dependencies {
    // Minecraft bundles Gson on every loader, so it is provided at runtime and
    // must NOT be shaded. The standalone applier deliberately avoids it.
    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("org.jetbrains:annotations:26.0.2")

    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}
