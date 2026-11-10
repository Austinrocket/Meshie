package com.meshie4LoRa.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meshie4LoRa.MeshChanApp
import com.meshie4LoRa.brain.DraftSink
import com.meshie4LoRa.ui.MainActivity
import kotlinx.coroutines.launch

/** Approve mode: each draft becomes a notification with Send / Discard actions. */
class DraftNotifier(private val context: Context) : DraftSink {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Reply drafts", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onDraft(decisionId: Long, text: String, targetName: String) {
        fun action(action: String, label: String) = Notification.Action.Builder(
            null, label,
            PendingIntent.getBroadcast(
                context, (decisionId * 2 + if (action == ACTION_SEND) 0 else 1).toInt(),
                Intent(context, DraftActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, decisionId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.meshie4LoRa.R.drawable.ic_stat_chan)
            .setContentTitle("Reply to $targetName?")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(action(ACTION_SEND, "Send"))
            .addAction(action(ACTION_DISCARD, "Discard"))
            .setAutoCancel(true)
            .build()
        nm.notify(notificationId(decisionId), n)
    }

    fun cancel(decisionId: Long) = nm.cancel(notificationId(decisionId))

    companion object {
        const val CHANNEL_ID = "drafts"
        const val ACTION_SEND = "com.meshie4LoRa.DRAFT_SEND"
        const val ACTION_DISCARD = "com.meshie4LoRa.DRAFT_DISCARD"
        const val EXTRA_ID = "decision_id"
        fun notificationId(decisionId: Long) = 1000 + (decisionId % 100_000).toInt()
    }
}

class DraftActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(DraftNotifier.EXTRA_ID, -1)
        if (id < 0) return
        val c = (context.applicationContext as MeshChanApp).container
        val pending = goAsync()
        c.appScope.launch {
            try {
                when (intent.action) {
                    DraftNotifier.ACTION_SEND -> c.brain.sendDraft(id)
                    DraftNotifier.ACTION_DISCARD -> c.brain.discardDraft(id)
                }
                c.drafts.cancel(id)
            } finally {
                pending.finish()
            }
        }
    }
}
