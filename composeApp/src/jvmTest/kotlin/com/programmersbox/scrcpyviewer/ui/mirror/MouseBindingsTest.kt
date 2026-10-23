package com.programmersbox.scrcpyviewer.ui.mirror

import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.ui.mirror.MouseBinding.AppSwitch
import com.programmersbox.scrcpyviewer.ui.mirror.MouseBinding.Back
import com.programmersbox.scrcpyviewer.ui.mirror.MouseBinding.Click
import com.programmersbox.scrcpyviewer.ui.mirror.MouseBinding.Disabled
import com.programmersbox.scrcpyviewer.ui.mirror.MouseBinding.ExpandNotificationPanel
import com.programmersbox.scrcpyviewer.ui.mirror.MouseBinding.Home
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MouseBindingsTest {
    @Test
    fun `parses two sets`() {
        // app/tests/test_cli.c has no --mouse-bind case; these follow parse_mouse_bindings() in cli.c.
        val b = assertNotNull(MouseBindings.parse("bhsn:++++"))
        assertEquals(MouseBindings("bhsn", "++++"), b)
        assertEquals(listOf(Back, Home, AppSwitch, ExpandNotificationPanel), b.primarySet())
        assertEquals(listOf(Click, Click, Click, Click), b.secondarySet())
    }

    @Test
    fun `single set is used for Shift too`() {
        assertEquals(MouseBindings("++++", "++++"), MouseBindings.parse("++++"))
        assertEquals(MouseBindings("-b-h", "-b-h"), MouseBindings.parse("-b-h"))
    }

    @Test
    fun `rejects bad length and characters`() {
        for (bad in listOf("", "bhs", "bhsnb", "bhsn:", "bhsn:+++", "bhsn:+++++", "bhsn++++", "bhsn;++++", "bhsx", "BHSN", "bhsn:++x+", " bhsn")) {
            assertNull(MouseBindings.parse(bad), "'$bad' must be rejected")
            assertNotNull(MouseBindings.error(bad), bad)
        }
        assertNull(MouseBindings.error("-+bh:snbh"))
    }

    @Test
    fun `defaults depend on the mouse mode as in cli c`() {
        assertEquals(MouseBindings("bhsn", "++++"), MouseBindings.default(sdkMouse = true))
        assertEquals(MouseBindings("++++", "bhsn"), MouseBindings.default(sdkMouse = false))
        assertEquals(MouseBindings("bhsn", "++++"), MouseBindings.forOptions(OptionValues(emptyMap())))
        assertEquals(MouseBindings("++++", "bhsn"), MouseBindings.forOptions(OptionValues(mapOf("mouse" to "uhid"))))
        assertEquals(MouseBindings("-+-+", "-+-+"), MouseBindings.forOptions(OptionValues(mapOf("mouse" to "uhid", "mouse-bind" to "-+-+"))))
    }

    @Test
    fun `default follows the effective mouse mode of cli c`() {
        fun forOptions(vararg entries: Pair<String, String?>) = MouseBindings.forOptions(OptionValues(mapOf(*entries)))
        val sdk = MouseBindings("bhsn", "++++")
        val other = MouseBindings("++++", "bhsn")
        assertEquals(sdk, forOptions("mouse" to "sdk"))
        assertEquals(other, forOptions("mouse" to "disabled"), "only the SDK mouse gets bhsn:++++ (cli.c:3201-3207)")
        // Without video playback an unset --mouse resolves to DISABLED (cli.c:3155-3157).
        assertEquals(other, forOptions("no-playback" to null))
        assertEquals(other, forOptions("no-video-playback" to null))
        assertEquals(other, forOptions("no-video" to null))
        assertEquals(sdk, forOptions("no-playback" to null, "mouse" to "sdk"))
        // No control or a camera: --mouse stays AUTO, not SDK.
        assertEquals(other, forOptions("no-control" to null))
        assertEquals(other, forOptions("video-source" to "camera"))
        assertEquals(MouseMode.Disabled, effectiveMouseMode(OptionValues(mapOf("no-playback" to null))))
        assertEquals(MouseMode.Sdk, effectiveMouseMode(OptionValues(emptyMap())))
        assertEquals(null, effectiveMouseMode(OptionValues(mapOf("no-control" to null))))
    }

    @Test
    fun `binding per button and shift`() {
        val b = MouseBindings("bhsn", "-+-+")
        assertEquals(Back, b.binding(MouseButton.Right, shift = false))
        assertEquals(Home, b.binding(MouseButton.Middle, shift = false))
        assertEquals(AppSwitch, b.binding(MouseButton.X1, shift = false))
        assertEquals(ExpandNotificationPanel, b.binding(MouseButton.X2, shift = false))
        assertEquals(Disabled, b.binding(MouseButton.Right, shift = true))
        assertEquals(Click, b.binding(MouseButton.Middle, shift = true))
        assertEquals(Click, b.binding(MouseButton.Left, shift = false), "left is always a click")
        assertEquals(Click, b.binding(MouseButton.Left, shift = true))
    }
}
