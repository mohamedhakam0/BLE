package com.example.ble

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val currentOnBack = rememberUpdatedState(onBack)
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = LocalContext.current.findComponentActivity()

    DisposableEffect(activity, lifecycleOwner, enabled) {
        if (activity == null) {
            onDispose { }
        } else {
            val callback = object : OnBackPressedCallback(enabled) {
                override fun handleOnBackPressed() {
                    currentOnBack.value.invoke()
                }
            }
            activity.onBackPressedDispatcher.addCallback(lifecycleOwner, callback)
            onDispose { callback.remove() }
        }
    }
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? {
    return when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findComponentActivity()
        else -> null
    }
}

