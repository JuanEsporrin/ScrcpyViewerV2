import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

val hostClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val isArm = arch == "aarch64" || arch == "arm64"
    when {
        os.contains("mac") -> if (isArm) "macosx-arm64" else "macosx-x86_64"
        os.contains("win") -> "windows-x86_64"
        else -> "linux-x86_64"
    }
}

kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.kotlinx.serialization.json)
            val ffmpeg = libs.versions.ffmpeg.get()
            val javacpp = libs.versions.javacpp.get()
            implementation("org.bytedeco:ffmpeg:$ffmpeg")
            implementation("org.bytedeco:ffmpeg:$ffmpeg:$hostClassifier")
            implementation("org.bytedeco:javacpp:$javacpp")
            implementation("org.bytedeco:javacpp:$javacpp:$hostClassifier")
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.programmersbox.scrcpyviewer.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "ScrcpyViewer"
            packageVersion = "1.0.0"
            appResourcesRootDir.set(layout.projectDirectory.dir("bundled"))
        }
    }
}
