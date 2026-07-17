plugins {
    `java-library`
}

description = "Agent Client Protocol (ACP) data model and JSON-RPC stdio client"

dependencies {
    api(libs.jackson.databind)
    api(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
