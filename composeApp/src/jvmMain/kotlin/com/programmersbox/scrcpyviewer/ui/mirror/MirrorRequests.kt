package com.programmersbox.scrcpyviewer.ui.mirror

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Serials the user asked to mirror (double-click or Mirror button). Consumed by the mirror window host. */
class MirrorRequests(
    private val requests: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 16),
) : SharedFlow<String> by requests {
    fun request(serial: String) {
        requests.tryEmit(serial)
    }
}
