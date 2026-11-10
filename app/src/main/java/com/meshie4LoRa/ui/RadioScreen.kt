package com.meshie4LoRa.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshie4LoRa.data.nodeIdString
import com.meshie4LoRa.mesh.MAX_LONG_NAME_BYTES
import com.meshie4LoRa.mesh.MAX_SHORT_NAME_BYTES

@Composable
private fun RadioNameEditor(vm: MainViewModel) {
    val myNode by vm.myNode.collectAsStateWithLifecycle()
    val ready by vm.configComplete.collectAsStateWithLifecycle()
    var longName by rememberSaveable(myNode?.longName) { mutableStateOf(myNode?.longName.orEmpty()) }
    var shortName by rememberSaveable(myNode?.shortName) { mutableStateOf(myNode?.shortName.orEmpty()) }
    val longBytes = longName.trim().toByteArray().size
    val shortBytes = shortName.trim().toByteArray().size
    val longOk = longBytes in 1..MAX_LONG_NAME_BYTES
    val shortOk = shortBytes in 1..MAX_SHORT_NAME_BYTES

    Text("Radio name on the mesh", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = longName,
        onValueChange = { longName = it },
        label = { Text("Long name") },
        supportingText = { Text("$longBytes / $MAX_LONG_NAME_BYTES bytes") },
        isError = !longOk,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = shortName,
        onValueChange = { shortName = it },
        label = { Text("Short name") },
        supportingText = { Text("$shortBytes / $MAX_SHORT_NAME_BYTES bytes") },
        isError = !shortOk,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { vm.renameRadio(longName, shortName) },
            enabled = ready && longOk && shortOk &&
                (longName.trim() != myNode?.longName || shortName.trim() != myNode?.shortName),
        ) { Text("Save name") }
        OutlinedButton(onClick = vm::announceName, enabled = ready) { Text("Announce name") }
    }
}

/** Shown on the home page only while Android may pause the bot with the screen off. */
@Composable
fun BatteryOptimizationBanner() {
    val ctx = LocalContext.current
    val power = ctx.getSystemService(PowerManager::class.java)
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(ctx.packageName)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.currentStateFlow.collect { if (it == Lifecycle.State.RESUMED) exempt = power.isIgnoringBatteryOptimizations(ctx.packageName) }
    }
    if (exempt) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Android may pause the bot when the screen is off.", style = MaterialTheme.typography.titleSmall)
            Text("Allow background running so the bot keeps listening in your bag.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Allow") }
        }
    }
}

@Composable
private fun BackgroundCard() {
    val ctx = LocalContext.current
    val power = ctx.getSystemService(PowerManager::class.java)
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(ctx.packageName)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.currentStateFlow.collect { if (it == Lifecycle.State.RESUMED) exempt = power.isIgnoringBatteryOptimizations(ctx.packageName) }
    }
    Text("Screen-off running", style = MaterialTheme.typography.titleMedium)
    Text(
        if (exempt) "✓ Battery optimization is off for Meshie."
        else "Battery optimization is on: Android may pause or kill the bot when the screen is off.",
        color = if (exempt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
    if (!exempt) {
        Button(onClick = {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            )
        }) { Text("Allow running in background") }
    }
    Text(
        "Also on Samsung: Settings → Battery → Background usage limits → Never sleeping apps → add Meshie. " +
            "The bot restarts by itself after a reboot.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
fun RadioScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val address by vm.deviceAddress.collectAsStateWithLifecycle()
    val name by vm.deviceName.collectAsStateWithLifecycle()
    val link by vm.linkState.collectAsStateWithLifecycle()
    val error by vm.linkError.collectAsStateWithLifecycle()
    val myNum by vm.myNodeNum.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val opChannel by vm.operatingChannel.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val results by vm.scanResults.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Device: ${name ?: "none"} · ${address ?: ""}")
        Text("Link: ${link.name.lowercase()}${myNum?.let { " · my node ${nodeIdString(it)}" } ?: ""}")
        error?.let { Text("Last error: $it", color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (scanning) vm.stopScan() else vm.startScan() }) { Text(if (scanning) "Stop scan" else "Scan for radios") }
            if (address != null) OutlinedButton(onClick = vm::forgetDevice) { Text("Forget radio") }
        }
        results.forEach { d ->
            Column(Modifier.fillMaxWidth().clickable { vm.selectDevice(d) }.padding(vertical = 8.dp)) {
                Text(d.name ?: "Unnamed radio", style = MaterialTheme.typography.titleMedium)
                Text("${d.address} · RSSI ${d.rssi}${if (d.bonded) " · paired" else ""}", style = MaterialTheme.typography.bodySmall)
            }
        }

        HorizontalDivider()
        BackgroundCard()

        HorizontalDivider()
        RadioNameEditor(vm)

        HorizontalDivider()
        Text("Operating channel", style = MaterialTheme.typography.titleMedium)
        if (channels.isEmpty()) Text("Connect to a radio to load its channels.", style = MaterialTheme.typography.bodySmall)
        channels.forEach { ch ->
            Row(Modifier.fillMaxWidth().clickable { vm.setOperatingChannel(ch.index) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = ch.index == opChannel, onClick = { vm.setOperatingChannel(ch.index) })
                Text("${ch.index}: ${ch.name} (${ch.role.name.lowercase()})")
            }
        }
    }
}
