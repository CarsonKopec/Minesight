import org.apache.commons.lang3.SystemUtils

// Shared library (NOT a standalone mod): its classes are compiled against
// Minecraft and shaded into each feature mod. Keeping it a plain library
// avoids Forge seeing a duplicate "minesightcore" mod in multi-project dev runs.

plugins {
    idea
    java
    id("gg.essential.loom") version "0.10.0.+"
    id("dev.architectury.architectury-pack200") version "0.1.3"
}

val baseGroup: String by project
val mcVersion: String by project

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}

loom {
    log4jConfigs.from(rootProject.file("log4j2.xml"))
    runConfigs {
        "client" {
            if (SystemUtils.IS_OS_MAC_OSX) {
                vmArgs.remove("-XstartOnFirstThread")
            }
        }
        remove(getByName("server"))
    }
    forge {
        pack200Provider.set(dev.architectury.pack200.java.Pack200Adapter())
    }
}

repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/maven/")
    maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")
}

dependencies {
    minecraft("com.mojang:minecraft:1.8.9")
    mappings("de.oceanlabs.mcp:mcp_stable:22-1.8.9")
    forge("net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9")

    // Compile-only here; each feature mod shades the actual library in.
    compileOnly("org.java-websocket:Java-WebSocket:1.5.7")
}

tasks.withType(JavaCompile::class) {
    options.encoding = "UTF-8"
}
