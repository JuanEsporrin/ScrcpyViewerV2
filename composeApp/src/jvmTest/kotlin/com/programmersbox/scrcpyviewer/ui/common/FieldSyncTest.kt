package com.programmersbox.scrcpyviewer.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals

class FieldSyncTest {
    @Test
    fun `echo of latest text clears pending`() {
        val s = FieldSync("").typed("7")
        assertEquals(FieldSync("7"), reconcile(s, "7"))
    }

    @Test
    fun `fast typing with lagging echoes keeps newest text`() {
        var s = FieldSync("").typed("1").typed("12").typed("123")
        s = reconcile(s, "1")
        assertEquals(FieldSync("123", listOf("12", "123")), s)
        s = reconcile(s, "12")
        assertEquals(FieldSync("123", listOf("123")), s)
        s = reconcile(s, "123")
        assertEquals(FieldSync("123"), s)
    }

    @Test
    fun `preset setting a previously typed value resyncs`() {
        var s = FieldSync("").typed("1080")
        s = reconcile(s, "1080")
        s = reconcile(s.typed("720"), "720")
        assertEquals("720", s.text)
        s = reconcile(s, "1080")
        assertEquals(FieldSync("1080"), s)
    }

    @Test
    fun `upstream empty after backspacing resyncs on recheck`() {
        var s = FieldSync("5").typed("")
        s = reconcile(s, "")
        assertEquals(FieldSync(""), s)
        // field later shows stale text only if state was not reset; upstream "" with text "x" must win
        assertEquals(FieldSync(""), reconcile(FieldSync("x"), ""))
    }
}
