package com.meshie4LoRa.radio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meshie4LoRa.MeshChanApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Restarts the bot after a reboot or app update so the phone can stay in a bag. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = (context.applicationContext as MeshChanApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                if (container.settings.deviceAddress.first() != null) RadioService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
