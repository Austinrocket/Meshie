package com.meshie4LoRa.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.util.Log
import com.meshie4LoRa.data.AppDatabase
import com.meshie4LoRa.data.MessageEntity
import com.meshie4LoRa.data.nodeIdString
import com.meshie4LoRa.mesh.MeshRepository
import com.meshie4LoRa.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Notifications for incoming DMs and channel messages, one per conversation, skipped for whatever is on screen. */
class MessageNotifier(private val context: Context, private val db: AppDatabase, private val mesh: MeshRepository, scope: CoroutineScope) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val recent = mutableMapOf<String, ArrayDeque<Notification.MessagingStyle.Message>>()

    /** What the operator is looking at ("feed", "dm:<node>", ...), or null while the app is in the background. */
    val visible = MutableStateFlow<String?>(null)

    init {
        nm.createNotificationChannel(NotificationChannel(CH_DM, "Direct messages", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_CHANNEL, "Channel messages", NotificationManager.IMPORTANCE_DEFAULT))
        scope.launch {
            mesh.incoming.collect { m -> runCatching { onMessage(m) }.onFailure { Log.w(TAG, "Notify failed", it) } }
        }
        scope.launch { visible.collect { screen -> screen?.let(::clearFor) } }
    }

    private suspend fun onMessage(m: MessageEntity) {
        val node = db.nodes().get(m.fromNode)
        if (node?.ignored == true) return
        val key = if (m.isDirect) "dm:${m.fromNode}" else "ch:${m.channel}"
        if (visible.value == screenFor(key)) return
        val sender = Person.Builder().setName(node?.displayName ?: nodeIdString(m.fromNode)).setKey(m.fromNode.toString()).build()
        val lines = synchronized(recent) {
            recent.getOrPut(key) { ArrayDeque() }.apply {
                addLast(Notification.MessagingStyle.Message(m.text, m.rxTime, sender))
                while (size > MAX_LINES) removeFirst()
            }.toList()
        }
        val style = Notification.MessagingStyle(Person.Builder().setName("Me").build()).apply {
            if (!m.isDirect) {
                conversationTitle = mesh.channels.value.firstOrNull { it.index == m.channel }?.name ?: "Channel ${m.channel}"
                isGroupConversation = true
            }
            lines.forEach { addMessage(it) }
        }
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN, if (m.isDirect) OPEN_DM else OPEN_FEED)
            .putExtra(EXTRA_NODE, m.fromNode)
        val n = Notification.Builder(context, if (m.isDirect) CH_DM else CH_CHANNEL)
            .setSmallIcon(com.meshie4LoRa.R.drawable.ic_stat_chan)
            .setStyle(style)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(PendingIntent.getActivity(context, key.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
        nm.notify(idFor(key), n)
    }

    private fun screenFor(key: String) = if (key.startsWith("ch:")) "feed" else key

    private fun clearFor(screen: String) {
        val keys = synchronized(recent) { recent.keys.filter { screenFor(it) == screen }.onEach { recent.remove(it) } }
        keys.forEach { nm.cancel(idFor(it)) }
    }

    private fun idFor(key: String) = 50_000 + (key.hashCode() and 0xFFFF)

    companion object {
        private const val TAG = "MeshChan.Notify"
        const val CH_DM = "dms"
        const val CH_CHANNEL = "channel_messages"
        const val EXTRA_OPEN = "open"
        const val EXTRA_NODE = "node"
        const val OPEN_DM = "dm"
        const val OPEN_FEED = "feed"
        private const val MAX_LINES = 6
    }
}
