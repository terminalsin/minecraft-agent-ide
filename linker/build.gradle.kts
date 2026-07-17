plugins {
    application
    alias(libs.plugins.shadow)
}

description = "Desktop linker: bridges Minecraft to ACP agents (Claude Code, Codex, ...)"

dependencies {
    implementation(project(":acp-core"))
    implementation(project(":bridge-protocol"))
    implementation(libs.java.websocket)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("dev.ghast.linker.LinkerMain")
}

tasks.shadowJar {
    archiveBaseName.set("minecraft-agent-linker")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
