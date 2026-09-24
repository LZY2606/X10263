plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.ktor:ktor-server-core-jvm:2.3.13")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.13")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:2.3.13")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:2.3.13")
    implementation("io.ktor:ktor-server-status-pages-jvm:2.3.13")
    implementation("ch.qos.logback:logback-classic:1.5.8")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:2.3.13")
}

application {
    mainClass.set("forensics.MainKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

kotlin {
    jvmToolchain(17)
}
