package com.programmersbox.scrcpyviewer.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import kotlinx.serialization.Serializable

/** The destinations of the navigation rail. */
@Serializable
sealed interface TopLevelRoute : NavKey {
    @Serializable
    data object Devices : TopLevelRoute

    @Serializable
    data object Presets : TopLevelRoute

    @Serializable
    data object Settings : TopLevelRoute

    companion object {
        /** Rail order. */
        val all: List<TopLevelRoute> get() = listOf(Devices, Presets, Settings)
    }
}

/**
 * Owns the Navigation 3 back stack for the rail. Rail destinations are peers, so the stack always
 * holds exactly the current one: switching replaces it, and there is nothing to go back to (the
 * desktop app has no top-level back).
 */
@Stable
class TopLevelNavigator(start: TopLevelRoute = TopLevelRoute.Devices) {
    val backStack: SnapshotStateList<TopLevelRoute> = mutableStateListOf(start)

    val current: TopLevelRoute get() = backStack.last()

    val canGoBack: Boolean get() = backStack.size > 1

    fun select(route: TopLevelRoute) {
        if (route == current) return
        // One write keeps the stack non-empty for NavDisplay at every point.
        backStack[backStack.lastIndex] = route
        if (backStack.size > 1) backStack.removeRange(0, backStack.lastIndex)
    }
}

private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/** Fade-through duration: the outgoing screen fades first, then the incoming one fades and settles. */
const val FADE_THROUGH_MILLIS = 300
private const val FADE_OUT_MILLIS = 90

/**
 * Material fade-through for switching between rail destinations: the old screen fades out quickly,
 * the new one fades in with a slight scale-up. Only alpha and scale animate (graphics layer), so the
 * screens are not re-laid out per frame.
 */
fun <T : Any> fadeThrough(): AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform = {
    val incoming = FADE_THROUGH_MILLIS - FADE_OUT_MILLIS
    (
        fadeIn(tween(incoming, delayMillis = FADE_OUT_MILLIS, easing = EmphasizedDecelerate)) +
            scaleIn(tween(incoming, delayMillis = FADE_OUT_MILLIS, easing = EmphasizedDecelerate), initialScale = 0.96f)
        ) togetherWith fadeOut(tween(FADE_OUT_MILLIS, easing = EmphasizedAccelerate))
}
