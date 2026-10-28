package com.programmersbox.scrcpyviewer.ui.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import com.programmersbox.scrcpyviewer.AppIcon
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.theme.isDark
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.dark
import org.jetbrains.jewel.intui.window.styling.defaults
import org.jetbrains.jewel.intui.window.styling.light
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.ui.component.styling.IconButtonMetrics
import org.jetbrains.jewel.ui.component.styling.IconButtonStyle
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.styling.DecoratedWindowColors
import org.jetbrains.jewel.window.styling.DecoratedWindowStyle
import org.jetbrains.jewel.window.styling.TitleBarColors
import org.jetbrains.jewel.window.styling.TitleBarMetrics
import org.jetbrains.jewel.window.styling.TitleBarStyle
import org.jetbrains.jewel.window.utils.clientRegion

/** What a title-bar slot can use: the window's [chrome], and [interactive] for clickable parts of the bar. */
interface AppTitleBarScope {
    val chrome: WindowChrome

    /**
     * Marks this element as clickable inside the custom title bar; the rest of the bar drags the window.
     * [key] must be unique within the window. No-op with the system title bar.
     */
    fun Modifier.interactive(key: String): Modifier
}

private object CustomTitleBarScope : AppTitleBarScope {
    override val chrome get() = WindowChrome.Custom
    override fun Modifier.interactive(key: String): Modifier = clientRegion(key)
}

private object SystemTitleBarScope : AppTitleBarScope {
    override val chrome get() = WindowChrome.System
    override fun Modifier.interactive(key: String): Modifier = this
}

/**
 * A top-level window whose title bar is the app's own ([WindowChrome.Custom], Jewel decorated window
 * painted from the Material scheme) or the OS one ([WindowChrome.System], plain [Window]). The
 * [titleBar] slot is laid out inside the custom bar (start-aligned after the window controls on
 * macOS / Windows, centred on Linux), or above [content] in System mode — render nothing there if
 * the window wants no extra strip. Only pass [WindowChrome.Custom] on JetBrains Runtime (see [windowChrome]).
 */
@Composable
fun AppWindow(
    onCloseRequest: () -> Unit,
    title: String,
    themeMode: ThemeMode,
    chrome: WindowChrome,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    alwaysOnTop: Boolean = false,
    icon: Painter? = AppIcon.painter,
    titleBar: @Composable AppTitleBarScope.() -> Unit,
    content: @Composable FrameWindowScope.() -> Unit,
) {
    when (chrome) {
        WindowChrome.System -> Window(
            onCloseRequest = onCloseRequest,
            state = state,
            visible = visible,
            title = title,
            icon = icon,
            alwaysOnTop = alwaysOnTop,
        ) {
            Column(Modifier.fillMaxSize()) {
                ScrcpyViewerTheme(themeMode) {
                    CompositionLocalProvider(LocalContentColor provides barColors(MaterialTheme.colorScheme).content) {
                        SystemTitleBarScope.titleBar()
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) { content() }
            }
        }
        WindowChrome.Custom -> ScrcpyViewerTheme(themeMode) {
            val bar = barColors(MaterialTheme.colorScheme)
            val dark = themeMode.isDark()
            IntUiTheme(
                theme = if (dark) JewelTheme.darkThemeDefinition() else JewelTheme.lightThemeDefinition(),
                styling = ComponentStyling.default().decoratedWindow(
                    windowStyle = decoratedWindowStyle(bar, dark),
                    titleBarStyle = titleBarStyle(bar, dark),
                ),
            ) {
                // Jewel's theme replaces LocalIndication; give the window Material's locals back.
                ScrcpyViewerTheme(themeMode) {
                    DecoratedWindow(
                        onCloseRequest = onCloseRequest,
                        state = state,
                        visible = visible,
                        title = title,
                        icon = icon,
                        alwaysOnTop = alwaysOnTop,
                    ) {
                        TitleBar { _ ->
                            Row(
                                Modifier.align(if (Os.isLinux) Alignment.CenterHorizontally else Alignment.Start),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CompositionLocalProvider(LocalContentColor provides bar.content) {
                                    CustomTitleBarScope.titleBar()
                                }
                            }
                        }
                        content()
                    }
                }
            }
        }
    }
}

/** Linux frame border (the only place Jewel draws one; macOS / Windows keep the native frame). */
private fun decoratedWindowStyle(bar: BarColors, dark: Boolean): DecoratedWindowStyle {
    val colors = DecoratedWindowColors(border = bar.border, borderInactive = bar.border)
    return if (dark) DecoratedWindowStyle.dark(colors) else DecoratedWindowStyle.light(colors)
}

@Composable
private fun titleBarStyle(bar: BarColors, dark: Boolean): TitleBarStyle {
    val hover = bar.content.copy(alpha = 0.08f)
    val pressed = bar.content.copy(alpha = 0.12f)
    val colors = TitleBarColors(
        background = bar.background,
        inactiveBackground = bar.inactiveBackground,
        content = bar.content,
        // Jewel draws a 1dp line under the bar; same colour as the bar so it reads as one surface with the rail.
        border = bar.background,
        fullscreenControlButtonsBackground = bar.background,
        // Linux pane buttons (GNOME style): neutral hover, close included.
        titlePaneButtonHoveredBackground = hover,
        titlePaneButtonPressedBackground = pressed,
        titlePaneCloseButtonHoveredBackground = hover,
        titlePaneCloseButtonPressedBackground = pressed,
        iconButtonHoveredBackground = hover,
        iconButtonPressedBackground = pressed,
        dropdownHoveredBackground = hover,
        dropdownPressedBackground = pressed,
    )
    val metrics = TitleBarMetrics.defaults(height = 38.dp, titlePaneButtonSize = DpSize(32.dp, 32.dp))
    val base = if (dark) TitleBarStyle.dark(colors, metrics) else TitleBarStyle.lightWithLightHeader(colors, metrics)
    // Pane buttons are only drawn by Jewel on Linux (macOS / Windows use the native ones): make them round.
    return TitleBarStyle(
        colors = base.colors,
        metrics = base.metrics,
        icons = base.icons,
        dropdownStyle = base.dropdownStyle,
        iconButtonStyle = base.iconButtonStyle,
        paneButtonStyle = base.paneButtonStyle.round(),
        paneCloseButtonStyle = base.paneCloseButtonStyle.round(),
    )
}

private fun IconButtonStyle.round(): IconButtonStyle =
    IconButtonStyle(colors, with(metrics) { IconButtonMetrics(CornerSize(50), borderWidth, padding, minSize) })
