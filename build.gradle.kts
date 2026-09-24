plugins {
    kotlin("jvm") version "2.4.0"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:3.3.3")
    implementation("io.ktor:ktor-server-cio-jvm:3.3.3")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.3.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.9.0")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("ch.qos.logback:logback-classic:1.5.12")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.3.3")
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("heapfx.MainKt")
}

tasks.test { useJUnitPlatform() }
