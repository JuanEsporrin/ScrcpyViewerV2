package com.programmersbox.scrcpyviewer.update

import dev.nucleusframework.updater.UpdateInfo

/** What the UI shows about a newer release. */
data class Release(
    val version: String,
    val htmlUrl: String,
    /** ISO-8601 instant, or null when the metadata has none. */
    val publishedAt: String? = null,
    /** The GitHub release notes; null when they could not be fetched (the UI still links to [htmlUrl]). */
    val body: String? = null,
)

/** The `owner/name` GitHub repo releases come from; the `scrcpyviewer.updateRepo` system property overrides it. */
internal fun updateRepo(): String = System.getProperty("scrcpyviewer.updateRepo") ?: "jakepurple13/ScrcpyViewerV2"

/** Release tags are `v<version>`; Nucleus' GitHub provider downloads from the same tag. */
internal fun releaseFor(version: String, repo: String = updateRepo(), publishedAt: String? = null) = Release(
    version = version,
    htmlUrl = "https://github.com/$repo/releases/tag/v$version",
    publishedAt = publishedAt?.takeIf { it.isNotBlank() },
)

internal fun UpdateInfo.toRelease(repo: String = updateRepo()) = releaseFor(version, repo, releaseDate)
