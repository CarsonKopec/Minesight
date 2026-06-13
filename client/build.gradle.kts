plugins {
    id("fabric-loom") version "1.17.11"
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String
base { archivesName.set(project.property("archives_base_name") as String) }

// Per-instance build dir so several dev clients can build+run in parallel
// (-Pminesight.buildSuffix=clientN -> build-clientN) without clobbering each
// other's output. Pair with --project-cache-dir and -Pminesight.runDir.
(project.findProperty("minesight.buildSuffix") as String?)?.let {
    layout.buildDirectory.set(layout.projectDirectory.dir("build-$it"))
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

repositories {
    // loom adds the rest
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
}

loom {
    // Client-only mod: no dedicated-server entrypoints.
    splitEnvironmentSourceSets()
    mods {
        create("minesight") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }
    // Per-instance run dir so the Control Panel can launch several dev clients
    // at once (-Pminesight.runDir=run-clientN); defaults to loom's "run".
    // -Pminesight.server=host[:port] makes the client auto-join that server on
    // launch (quick play) instead of dropping to the main menu.
    runs {
        named("client") {
            (project.findProperty("minesight.runDir") as String?)?.let { runDir(it) }
            (project.findProperty("minesight.server") as String?)?.let {
                programArgs("--quickPlayMultiplayer", it)
            }
        }
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}
