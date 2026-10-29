package com.programmersbox.scrcpyviewer.update

import com.programmersbox.scrcpyviewer.APP_VERSION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.koin.core.annotation.Provided
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Where the latest release comes from. Given to Koin at start (tests give one that never touches the network). */
@Provided
fun interface ReleaseSource {
    suspend fun latest(): Release
}

class GitHubReleaseSource(
    private val apiBase: String = "https://api.github.com",
    private val repo: String = System.getProperty("scrcpyviewer.updateRepo") ?: "jakepurple13/ScrcpyViewerV2",
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) : ReleaseSource {
    override suspend fun latest(): Release = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create("$apiBase/repos/$repo/releases/latest"))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ScrcpyViewer/$APP_VERSION")
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw UpdateException("GitHub returned HTTP ${response.statusCode()}")
        }
        try {
            parseRelease(response.body())
        } catch (e: SerializationException) {
            throw UpdateException("Unexpected response from GitHub", e)
        } catch (e: IllegalArgumentException) {
            throw UpdateException("Unexpected response from GitHub", e)
        }
    }
}
