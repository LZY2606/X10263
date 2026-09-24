plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core:2.3.13")
    implementation("io.ktor:ktor-server-netty:2.3.13")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("ch.qos.logback:logback-classic:1.5.8")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(17) }

application { mainClass.set("heaproom.MainKt") }

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
