package com.meshie4LoRa.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/** Shows each message a view model posts to [toast] once, then clears it. */
@Composable
fun Toasts(toast: StateFlow<String?>, clear: () -> Unit, long: Boolean = false) {
    val ctx = LocalContext.current
    val message by toast.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(ctx, it, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            clear()
        }
    }
}
