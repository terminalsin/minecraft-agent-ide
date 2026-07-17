plugins {
    java
    alias(libs.plugins.shadow)
}

description = "Paper plugin: control ACP AI agents through villager NPCs"

dependencies {
    compileOnly(libs.paper.api)
    implementation(project(":bridge-protocol"))
    implementation(libs.java.websocket)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveBaseName.set("MinecraftAgentIDE")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()

    // Relocate bundled libraries so the plugin never clashes with other plugins on the server.
    relocate("com.fasterxml.jackson", "dev.ghast.mcagent.libs.jackson")
    relocate("org.java_websocket", "dev.ghast.mcagent.libs.websocket")

    // slf4j-api is provided by the Paper runtime; do not bundle it.
    dependencies {
        exclude(dependency("org.slf4j:.*"))
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
