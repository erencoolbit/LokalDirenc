import java.time.Duration

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    // Emülatörle uçtan uca denemede karşı cihaz rolünü oynayan geliştirici aracı.
    mainClass.set("org.lokaldirenc.core.cli.PeerKt")
}

dependencies {
    api("org.bouncycastle:bcprov-jdk18on:1.80")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    timeout.set(Duration.ofMinutes(4))
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
