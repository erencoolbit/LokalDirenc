plugins {
    id("com.android.application") version "8.9.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.21" apply false
}

// Proje OneDrive altında. Derleme çıktıları eşitleme kilitlerine takılmasın diye dışarıda tutulur.
val localAppData: String? = System.getenv("LOCALAPPDATA")
if (localAppData != null) {
    allprojects {
        layout.buildDirectory.set(file("$localAppData/LokalDirenc/build/${project.name}"))
    }
}
