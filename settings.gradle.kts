rootProject.name = "minecraft-agent-ide"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
        maven("https://oss.sonatype.org/content/repositories/snapshots/") {
            name = "sonatype-snapshots"
        }
    }
}

include(
    "acp-core",
    "bridge-protocol",
    "linker",
    "mc-plugin",
)
