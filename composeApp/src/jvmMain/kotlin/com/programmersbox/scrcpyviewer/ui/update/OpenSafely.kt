package com.programmersbox.scrcpyviewer.ui.update

import androidx.compose.ui.platform.UriHandler

private val log = System.getLogger("scrcpyviewer.Update")

/** Opens [url] in the browser; a failure is logged, never thrown. */
fun UriHandler.openSafely(url: String) {
    runCatching { openUri(url) }
        .onFailure { log.log(System.Logger.Level.WARNING, "Could not open $url", it) }
}
