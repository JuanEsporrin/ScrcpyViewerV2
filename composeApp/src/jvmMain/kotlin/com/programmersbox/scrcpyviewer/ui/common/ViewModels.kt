package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The scope a ViewModel's `viewModelScope` gets by default (main thread, cancelled when the ViewModel is cleared).
 * ViewModels take it as a default parameter so tests can pass their own; Koin leaves default parameters alone.
 */
fun defaultViewModelScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** A ViewModel store that lives while the caller is composed, e.g. a dialog's; its ViewModels are cleared after. */
@Composable
fun rememberComposedViewModelStoreOwner(): ViewModelStoreOwner {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return owner
}
