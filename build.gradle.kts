plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core:3.2.3")
    implementation("io.ktor:ktor-server-netty:3.2.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("heapforensics.MainKt")
}

sourceSets {
    main {
        resources.srcDir("casepack")
    }
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    into("casepack") {
        from("casepack")
    }
}

tasks.test { useJUnitPlatform() }
