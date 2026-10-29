package com.programmersbox.scrcpyviewer.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A downloadable file attached to a GitHub release. [digest] is `sha256:<hex>` when GitHub provides it. */
@Serializable
data class ReleaseAsset(
    val name: String,
    val size: Long,
    @SerialName("browser_download_url") val downloadUrl: String,
    val digest: String? = null,
)

/** Subset of the GitHub "latest release" response used by the updater. */
@Serializable
data class Release(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<ReleaseAsset> = emptyList(),
) {
    /** Parsed [tagName], or null when it is not a plain numeric version. */
    val version: Version? get() = Version.parse(tagName)
}

/** Dotted numeric version; missing trailing parts compare as 0. */
data class Version(val parts: List<Int>) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val c = (parts.getOrElse(i) { 0 }).compareTo(other.parts.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    override fun toString(): String = parts.joinToString(".")

    companion object {
        /** Parses `1.2.3` or `v1.2.3`; returns null if any part is not an integer. */
        fun parse(text: String): Version? {
            val parts = text.trim().removePrefix("v").removePrefix("V").split('.').map { it.toIntOrNull() ?: return null }
            return Version(parts)
        }
    }
}

/** Platforms with a published installer; each maps to the installer file extension. */
enum class UpdatePlatform(val extension: String?) {
    MacArm64("dmg"),
    WindowsX64("msi"),
    LinuxX64("deb"),
    Unsupported(null);

    companion object {
        fun detect(
            osName: String = System.getProperty("os.name"),
            arch: String = System.getProperty("os.arch"),
        ): UpdatePlatform {
            val os = osName.lowercase()
            val a = arch.lowercase()
            val x64 = a == "amd64" || a == "x86_64"
            return when {
                "mac" in os -> if (a == "aarch64" || a == "arm64") MacArm64 else Unsupported
                "win" in os -> if (x64) WindowsX64 else Unsupported
                "linux" in os -> if (x64) LinuxX64 else Unsupported
                else -> Unsupported
            }
        }
    }
}

/** The installer asset for [platform], or null when none is published. */
fun Release.assetFor(platform: UpdatePlatform): ReleaseAsset? {
    val ext = platform.extension ?: return null
    return assets.firstOrNull { it.name.endsWith(".$ext", ignoreCase = true) }
}

private val releaseJson = Json { ignoreUnknownKeys = true }

fun parseRelease(json: String): Release = releaseJson.decodeFromString(json)
