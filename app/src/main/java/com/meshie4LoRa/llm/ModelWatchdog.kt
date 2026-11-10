package com.meshie4LoRa.llm

import android.util.Log
import com.meshie4LoRa.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "MeshChan.Watchdog"

/** Keeps the model usable unattended: reloads it after a failure/unload and cancels generations that hang. */
class ModelWatchdog(
    private val engine: LlamaEngine,
    private val models: ModelManager,
    private val settings: SettingsStore,
    scope: CoroutineScope,
) {
    @Volatile private var busySince: Long? = null
    private var notReadySince: Long? = null
    private var attempts = 0
    private var nextAttemptAt = 0L

    init {
        scope.launch { engine.busy.collect { busySince = if (it) System.currentTimeMillis() else null } }
        scope.launch {
            while (true) {
                delay(CHECK_MS)
                try {
                    check(System.currentTimeMillis())
                } catch (e: Exception) {
                    Log.e(TAG, "Watchdog check failed", e)
                }
            }
        }
    }

    private suspend fun check(now: Long) {
        busySince?.let { since ->
            if (now - since > STUCK_MS) {
                Log.w(TAG, "Generation running for ${(now - since) / 1000} s; cancelling")
                engine.cancel()
                busySince = null
            }
        }
        when (engine.state.value) {
            is EngineState.Ready -> { notReadySince = null; attempts = 0; return }
            is EngineState.Loading -> return
            else -> Unit
        }
        if (settings.activeModelId.first() == null) return
        val since = notReadySince ?: now.also { notReadySince = it }
        if (now - since < GRACE_MS || now < nextAttemptAt) return
        attempts++
        nextAttemptAt = now + BACKOFF_MS[minOf(attempts, BACKOFF_MS.size) - 1]
        Log.w(TAG, "Model not ready (${engine.state.value}); reload attempt $attempts")
        models.loadActive()
    }

    companion object {
        const val CHECK_MS = 30_000L
        /** Longest a single generation may run (3B screen-off replies take ~2.5 min). */
        const val STUCK_MS = 5 * 60_000L
        const val GRACE_MS = 60_000L
        private val BACKOFF_MS = longArrayOf(60_000, 2 * 60_000, 5 * 60_000, 15 * 60_000)
    }
}
