plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core:2.3.13")
    implementation("io.ktor:ktor-server-netty:2.3.13")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.slf4j:slf4j-simple:2.0.16")
    testImplementation("io.ktor:ktor-server-test-host:2.3.13")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("heapsleuth.MainKt")
}

tasks.test { useJUnitPlatform() }
