import org.apache.commons.lang3.SystemUtils

plugins {
    idea
    java
    id("gg.essential.loom") version "0.10.0.+"
    id("dev.architectury.architectury-pack200") version "0.1.3"
}

val baseGroup: String by project
val mcVersion: String by project
val modid = "minesightdetection"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}

loom {
    log4jConfigs.from(rootProject.file("log4j2.xml"))
    launchConfigs {
        "client" {
            (project.findProperty("minesight.autoworld") as String?)?.let {
                property("minesight.autoworld", it)
            }
        }
    }
    runConfigs {
        "client" {
            runDir((project.findProperty("minesight.runDir") as String?) ?: "run")
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

sourceSets.main {
    output.setResourcesDir(sourceSets.main.flatMap { it.java.classesDirectory })
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

    implementation(project(":core"))

    runtimeOnly("me.djtheredstoner:DevAuth-forge-legacy:1.2.1")
}

tasks.withType(JavaCompile::class) {
    options.encoding = "UTF-8"
}

tasks.withType(org.gradle.jvm.tasks.Jar::class) {
    archiveBaseName.set(modid)
}

tasks.named<JavaExec>("runClient") {
    val pinnedJava = file("C:\\Users\\kopec\\.gradle\\jdks\\temurin-8-amd64-windows\\jdk8u492-b09\\bin\\java.exe")
    if (pinnedJava.exists()) {
        executable = pinnedJava.absolutePath
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("mcversion", mcVersion)
    inputs.property("modid", modid)
    filesMatching("mcmod.info") {
        expand(mapOf("version" to project.version, "mcversion" to mcVersion, "modid" to modid))
    }
}

tasks.jar {
    archiveClassifier.set("dev")
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveClassifier.set("")
}

tasks.assemble.get().dependsOn(tasks.named("remapJar"))
