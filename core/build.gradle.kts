import java.time.Duration

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
            timeout.set(Duration.ofMinutes(4))
            testLogging {
                events("passed", "failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }

    // Yalnız taşınabilirlik denetimi: iOS bu makinede derlenmez, ama ortak kod Java'ya
    // bağımlı kalırsa bu hedefin derlemesi kırılır. Uygulama olarak dağıtılmaz.
    mingwX64()

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            implementation("org.jetbrains.kotlinx:atomicfu:0.27.0")
        }
        jvmMain.dependencies {
            api("org.bouncycastle:bcprov-jdk18on:1.80")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Emülatörle uçtan uca denemede karşı cihaz rolünü oynayan geliştirici aracı.
tasks.register<JavaExec>("runPeer") {
    val main = kotlin.jvm().compilations.getByName("main")
    dependsOn(main.compileTaskProvider)
    classpath = main.output.allOutputs + main.runtimeDependencyFiles!!
    mainClass.set("org.lokaldirenc.core.cli.PeerKt")
}
