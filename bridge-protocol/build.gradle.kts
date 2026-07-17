plugins {
    `java-library`
}

description = "Shared message contract exchanged between the Minecraft plugin and the desktop linker"

dependencies {
    api(libs.jackson.databind)
    api(libs.jackson.annotations)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
