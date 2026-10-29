package com.programmersbox.scrcpyviewer.ui.update

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class UpdatesSectionTest {
    private val now = Instant.parse("2026-10-29T12:00:00Z")

    @Test
    fun `under a minute is just now`() = assertEquals("just now", lastCheckedText(now - Duration.ofSeconds(30), now))

    @Test
    fun `under an hour is minutes`() = assertEquals("5 min ago", lastCheckedText(now - Duration.ofMinutes(5), now))

    @Test
    fun `an hour or more is hours`() = assertEquals("2 h ago", lastCheckedText(now - Duration.ofHours(2), now))
}
