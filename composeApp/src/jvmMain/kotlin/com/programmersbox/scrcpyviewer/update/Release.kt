package com.programmersbox.scrcpyviewer.update

import dev.nucleusframework.updater.UpdateInfo

/**
 * What the UI shows about a newer release. Built from Nucleus' update metadata (`latest-*.yml`), which has no
 * release notes, so the UI links to [htmlUrl] for them.
 */
data class Release(
    val version: String,
    val htmlUrl: String,
    /** ISO-8601 instant, or null when the metadata has none. */
    val publishedAt: String? = null,
)

/** The `owner/name` GitHub repo releases come from; the `scrcpyviewer.updateRepo` system property overrides it. */
internal fun updateRepo(): String = System.getProperty("scrcpyviewer.updateRepo") ?: "jakepurple13/ScrcpyViewerV2"

/** Release tags are `v<version>`; Nucleus' GitHub provider downloads from the same tag. */
internal fun UpdateInfo.toRelease(repo: String = updateRepo()) = Release(
    version = version,
    htmlUrl = "https://github.com/$repo/releases/tag/v$version",
    publishedAt = releaseDate.takeIf { it.isNotBlank() },
)
