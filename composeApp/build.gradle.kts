import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile
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

val scrcpyServerUrl = "https://github.com/Genymobile/scrcpy/releases/download/v5.0/scrcpy-server-v5.0"
// SHA-256 of scrcpy-server v5.0 (matches the GitHub release asset digest).
val scrcpyServerSha256 = "26cbc9ad0aced6c2282455bef4fb43462605c1f8758c74b4ab1dbf818c229daa"

// platform-tools zip platform -> (bundled folder, files to keep from platform-tools/)
val platformToolsTargets = listOf(
    Triple("darwin", "macos-arm64", listOf("adb", "NOTICE.txt")),
    Triple("windows", "windows-x64", listOf("adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll", "NOTICE.txt")),
    Triple("linux", "linux-x64", listOf("adb", "NOTICE.txt")),
)

val bundledDir = layout.projectDirectory.dir("bundled")
val downloadCacheDir = layout.buildDirectory.dir("bundled-downloads")

val downloadBundledTools = tasks.register("downloadBundledTools") {
    group = "build setup"
    description = "Downloads scrcpy-server 5.0 and Android platform-tools adb into composeApp/bundled."
    val serverOut = bundledDir.file("common/scrcpy-server")
    val adbOutputs = platformToolsTargets.flatMap { (_, folder, files) ->
        files.map { bundledDir.file("$folder/$it") }
    }
    outputs.files(serverOut, adbOutputs)
    // Up to date when every output file exists.
    outputs.upToDateWhen { (listOf(serverOut) + adbOutputs).all { it.asFile.isFile } }
    val cache = downloadCacheDir
    val serverUrl = scrcpyServerUrl
    val serverSha = scrcpyServerSha256
    val targets = platformToolsTargets
    val root = bundledDir
    doLast {
        fun download(url: String, dest: File) {
            dest.parentFile.mkdirs()
            URI(url).toURL().openStream().use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }

        val server = serverOut.asFile
        download(serverUrl, server)
        val actual = MessageDigest.getInstance("SHA-256").digest(server.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (actual != serverSha) {
            server.delete()
            error("scrcpy-server SHA-256 mismatch: expected $serverSha but got $actual")
        }

        for ((os, folder, keep) in targets) {
            val zip = cache.get().asFile.resolve("platform-tools-latest-$os.zip")
            download("https://dl.google.com/android/repository/platform-tools-latest-$os.zip", zip)
            val outDir = root.asFile.resolve(folder).also { it.mkdirs() }
            ZipFile(zip).use { zf ->
                for (name in keep) {
                    val entry = zf.getEntry("platform-tools/$name") ?: error("$name missing from $zip")
                    val out = outDir.resolve(name)
                    zf.getInputStream(entry).use { ins -> out.outputStream().use { ins.copyTo(it) } }
                    if (!name.contains('.') || name == "adb.exe") out.setExecutable(true)
                }
            }
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(downloadBundledTools) }

tasks.matching { it.name == "run" }.configureEach {
    dependsOn(downloadBundledTools)
    (this as? JavaExec)?.systemProperty("scrcpyviewer.bundledDir", bundledDir.asFile.absolutePath)
}
