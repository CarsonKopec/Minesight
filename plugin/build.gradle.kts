plugins {
    java
    // Adds `./gradlew runServer`: downloads the server, stages our plugin jar
    // into plugins/, and launches it - the server-side analogue of Loom's
    // runClient. The Folia scheduler APIs this plugin uses also exist on Paper
    // (they just run on the main thread there), so Paper is a faithful dev
    // server; a real Folia jar is only needed to exercise true regionization.
    id("xyz.jpenilla.run-paper") version "3.0.2"
    // NMS access (Mojang-mapped server internals) for real ServerPlayer bots.
    // Requires Gradle 9 (see gradle/wrapper/gradle-wrapper.properties).
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

group = "com.minesight"
version = "2.0.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Full Mojang-mapped Paper server: the Bukkit/Paper API plus net.minecraft
    // server internals (NMS). Replaces the old compileOnly paper-api.
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

tasks.runServer {
    minecraftVersion("1.21.11")
    // First launch writes run/eula.txt and stops; set eula=true there to accept
    // the Mojang EULA, then run again. For a no-account dev server also set
    // online-mode=false in run/server.properties.
}
