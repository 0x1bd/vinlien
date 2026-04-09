plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.ktor)
}

application {
    mainClass.set("org.kvxd.vinlien.server.ApplicationKt")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":backend:backends"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.call.logging)

    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)

    implementation(libs.logback.classic)
    implementation(libs.slf4j.api)

    implementation(libs.jbcrypt)
    implementation(libs.ktor.client.cio)
}

val buildFrontend by tasks.registering(Exec::class) {
    workingDir = file("../../frontend")

    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    commandLine(if (isWindows) "npm.cmd" else "npm", "run", "build")

    onlyIf { System.getenv("SKIP_NPM") != "true" }
}

tasks.named("processResources") {
    dependsOn(buildFrontend)
}