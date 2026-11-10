package com.meshie4LoRa

import android.content.Context
import com.meshie4LoRa.brain.BotBrain
import com.meshie4LoRa.brain.CharacterRepository
import com.meshie4LoRa.radio.DraftNotifier
import com.meshie4LoRa.data.AppDatabase
import com.meshie4LoRa.data.SettingsStore
import com.meshie4LoRa.llm.LlamaEngine
import com.meshie4LoRa.llm.ModelManager
import com.meshie4LoRa.llm.ModelWatchdog
import com.meshie4LoRa.mesh.MeshRepository
import com.meshie4LoRa.memory.MemoryManager
import com.meshie4LoRa.radio.BleRadioTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.create(context)
    val settings = SettingsStore(context)
    val transport = BleRadioTransport(context, appScope)
    val mesh = MeshRepository(transport, db, appScope, settings)
    val engine = LlamaEngine()
    val models = ModelManager(context, db, settings, engine, appScope)
    val characters = CharacterRepository(db, mesh, settings, appScope)
    val drafts = DraftNotifier(context)
    val notifier = com.meshie4LoRa.radio.MessageNotifier(context, db, mesh, appScope)
    val brain = BotBrain(db, mesh, settings, characters, engine, appScope).also { it.draftSink = drafts }
    val memory = MemoryManager(db, mesh, settings, characters, engine, brain, appScope)
    val watchdog = ModelWatchdog(engine, models, settings, appScope)
}
