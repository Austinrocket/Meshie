package com.meshie4LoRa.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.meshie4LoRa.MeshChanApp
import com.meshie4LoRa.brain.BotMode
import com.meshie4LoRa.data.CharacterEntity
import com.meshie4LoRa.data.SLEEP_FOREVER
import com.meshie4LoRa.llm.EngineState
import com.meshie4LoRa.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Keeps the radio link (and later the bot brain) alive with the screen off. */
class RadioService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "radio"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) = context.startForegroundService(Intent(context, RadioService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, RadioService::class.java))
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Radio link", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeshChan:radio")
            .apply { setReferenceCounted(false) }

        val container = (application as MeshChanApp).container
        lifecycleScope.launch {
            // Keep the CPU awake only while the bot can actually act; otherwise let the phone sleep between radio packets.
            val canWork = combine(container.transport.state, container.engine.state, container.settings.botMode) { link, engine, mode ->
                link == LinkState.CONNECTED && engine is EngineState.Ready && mode != BotMode.MANUAL
            }
            combine(canWork, container.settings.sleepUntil, container.engine.busy, container.engine.state, ticker()) { can, sleep, busy, engine, now ->
                busy || engine is EngineState.Loading || (can && sleep <= now)
            }.distinctUntilChanged().collect { hold ->
                wakeLock?.let { if (hold) it.acquire() else if (it.isHeld) it.release() }
            }
        }
        lifecycleScope.launch {
            container.settings.deviceAddress.distinctUntilChanged().collect { address ->
                if (address != null) container.transport.connect(address) else container.transport.disconnect()
            }
        }
        lifecycleScope.launch {
            val link = combine(container.transport.state, container.mesh.radioLabel, container.transport.lastError) { s, n, e -> Triple(s, n, e) }
            val bot = combine(container.settings.sleepUntil, container.settings.botMode, container.characters.active) { s, m, c -> Triple(s, m, c) }
            val work = combine(container.engine.state, container.brain.status, container.memory.status) { e, b, m -> Triple(e, b, m) }
            combine(link, bot, work, ticker()) { l, b, w, now -> statusText(l, b, w, now) }
                .distinctUntilChanged()
                .collect { (title, text) -> nm.notify(NOTIFICATION_ID, buildNotification(text, title)) }
        }
    }

    /** Re-evaluates once a minute so "asleep until" flips to awake without another trigger. */
    private fun ticker() = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    private fun statusText(
        link: Triple<LinkState, String?, String?>,
        bot: Triple<Long, BotMode, CharacterEntity?>,
        work: Triple<EngineState, String?, String?>,
        now: Long,
    ): Pair<String, String> {
        val (state, device, error) = link
        val (sleepUntil, mode, character) = bot
        val (engine, brainStatus, memoryStatus) = work
        val name = character?.longName ?: "No persona"
        val title = when {
            sleepUntil == SLEEP_FOREVER -> "$name · off"
            sleepUntil > now -> "$name · asleep until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(sleepUntil))}"
            else -> "$name · ${mode.label}"
        }
        val text = when {
            state != LinkState.CONNECTED -> "Radio ${device ?: ""} ${state.name.lowercase()}" + (error?.let { ": $it" } ?: "")
            engine is EngineState.Failed -> "Model failed to load; retrying"
            engine is EngineState.Loading -> "Loading model…"
            engine !is EngineState.Ready -> "No model loaded"
            brainStatus != null -> brainStatus
            memoryStatus != null -> memoryStatus
            else -> "Listening on ${device ?: "radio"}"
        }
        return title to text
    }

    override fun onDestroy() {
        (application as MeshChanApp).container.transport.disconnect()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
        // After super: the status collector is cancelled, so it can't re-post this.
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun buildNotification(text: String, title: String = "Meshie"): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.meshie4LoRa.R.drawable.ic_stat_chan)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
