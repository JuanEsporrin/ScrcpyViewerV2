package com.programmersbox.scrcpyviewer.update

import com.programmersbox.scrcpyviewer.APP_VERSION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

open class UpdateDownloader(
    private val updatesDir: Path,
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) {
    /** Downloads [asset] into `updatesDir/<version>/`, verifies its SHA-256, and returns the final path. */
    open suspend fun download(
        asset: ReleaseAsset,
        version: Version,
        onProgress: (read: Long, total: Long) -> Unit,
    ): Path {
        val versionDir = updatesDir.resolve(version.toString())
        val target = versionDir.resolve(asset.name)
        val part = versionDir.resolve("${asset.name}.part")
        try {
            withContext(Dispatchers.IO) {
                removeOtherVersions(version.toString())
                Files.createDirectories(versionDir)
                stream(asset, part, onProgress)
                verifySha256(part, asset.digest)
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
            }
            return target
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(part) }
            runCatching { Files.deleteIfExists(target) }
            throw e
        }
    }

    private suspend fun stream(asset: ReleaseAsset, part: Path, onProgress: (Long, Long) -> Unit) {
        val request = HttpRequest.newBuilder(URI.create(asset.downloadUrl))
            .header("User-Agent", "ScrcpyViewer/$APP_VERSION")
            .GET()
            .build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()
        response.body().use { input ->
            if (response.statusCode() !in 200..299) {
                throw UpdateException("Download failed: HTTP ${response.statusCode()}")
            }
            val total = asset.size
            val ctx = currentCoroutineContext()
            // runInterruptible interrupts the blocked read when the coroutine is cancelled.
            runInterruptible(Dispatchers.IO) {
                var read = 0L
                val buffer = ByteArray(CHUNK)
                Files.newOutputStream(part).use { out ->
                    while (true) {
                        ctx.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
        }
    }

    private fun removeOtherVersions(keep: String) {
        if (!Files.isDirectory(updatesDir)) return
        Files.newDirectoryStream(updatesDir).use { entries ->
            for (entry in entries) {
                if (Files.isDirectory(entry) && entry.fileName.toString() != keep) {
                    entry.toFile().deleteRecursively()
                }
            }
        }
    }

    fun verifySha256(file: Path, digest: String?) {
        if (digest == null || !digest.startsWith(PREFIX)) throw UpdateException("Release file has no checksum")
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(digest.removePrefix(PREFIX), ignoreCase = true)) {
            throw UpdateException("Downloaded file is corrupted (checksum mismatch)")
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024
        const val PREFIX = "sha256:"
    }
}
