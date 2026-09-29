plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:3.2.3")
    implementation("io.ktor:ktor-server-netty-jvm:3.2.3")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.2.3")
}

kotlin { jvmToolchain(17) }

application { mainClass.set("hf.MainKt") }

tasks.test { useJUnitPlatform(); testLogging { events("passed", "failed") } }
