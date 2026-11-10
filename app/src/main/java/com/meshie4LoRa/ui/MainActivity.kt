package com.meshie4LoRa.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val modelsVm: ModelsViewModel by viewModels()
    private val characterVm: CharacterViewModel by viewModels()
    private val memoryVm: MemoryViewModel by viewModels()
    private val statsVm: com.meshie4LoRa.ui.stats.StatsViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    private fun requestRadioPermissions() =
        permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))

    private fun requestAllPermissions() = permissionLauncher.launch(
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                val touring by vm.touring.collectAsStateWithLifecycle()
                when (touring) {
                    null -> Unit
                    true -> com.meshie4LoRa.ui.onboarding.OnboardingScreen(
                        vm, modelsVm, characterVm, statsVm,
                        replay = vm.replaying,
                        onRequestRadioPermissions = ::requestRadioPermissions,
                        onRequestNotifications = { permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) },
                    )
                    false -> {
                        LaunchedEffect(Unit) { requestAllPermissions() }
                        App(vm, modelsVm, characterVm, memoryVm, statsVm)
                    }
                }
            }
        }
        handleOpen(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    /** Notification taps: open the DM chat or the Feed. */
    private fun handleOpen(intent: android.content.Intent?) {
        when (intent?.getStringExtra(com.meshie4LoRa.radio.MessageNotifier.EXTRA_OPEN)) {
            com.meshie4LoRa.radio.MessageNotifier.OPEN_DM ->
                vm.openPerson(intent.getLongExtra(com.meshie4LoRa.radio.MessageNotifier.EXTRA_NODE, 0L), false)
            com.meshie4LoRa.radio.MessageNotifier.OPEN_FEED -> vm.openFeed()
        }
        intent?.removeExtra(com.meshie4LoRa.radio.MessageNotifier.EXTRA_OPEN)
    }
}

private enum class Tab(val label: String) { CHAN("Chan"), FEED("Feed"), PEOPLE("People"), SETTINGS("Settings") }

@Composable
private fun App(
    vm: MainViewModel,
    modelsVm: ModelsViewModel,
    characterVm: CharacterViewModel,
    memoryVm: MemoryViewModel,
    statsVm: com.meshie4LoRa.ui.stats.StatsViewModel,
) {
    var tab by rememberSaveable { mutableIntStateOf(Tab.CHAN.ordinal) }
    val deviceAddress by vm.deviceAddress.collectAsStateWithLifecycle()
    val unread by vm.unreadByNode.collectAsStateWithLifecycle()
    val totalUnread = unread.values.sum()

    LaunchedEffect(deviceAddress) { vm.ensureServiceRunning() }
    val openPerson by vm.openPerson.collectAsStateWithLifecycle()
    LaunchedEffect(openPerson) { if (openPerson != null) tab = Tab.PEOPLE.ordinal }
    val openFeed by vm.openFeed.collectAsStateWithLifecycle()
    LaunchedEffect(openFeed) { if (openFeed) { tab = Tab.FEED.ordinal; vm.consumeOpenFeed() } }
    val lifecycleState by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val viewingPerson by vm.viewingPerson.collectAsStateWithLifecycle()
    LaunchedEffect(tab, viewingPerson, lifecycleState) {
        val started = lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        vm.setVisibleScreen(
            when {
                !started -> null
                tab == Tab.FEED.ordinal -> "feed"
                tab == Tab.PEOPLE.ordinal && viewingPerson != null -> "dm:$viewingPerson"
                else -> Tab.entries[tab].name.lowercase()
            }
        )
    }
    Toasts(vm.toast, vm::clearToast, long = true)

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t.ordinal,
                        onClick = { tab = t.ordinal },
                        icon = {
                            BadgedBox(badge = { if (t == Tab.PEOPLE && totalUnread > 0) Badge { Text("$totalUnread") } }) {
                                Icon(
                                    when (t) {
                                        Tab.CHAN -> Icons.Filled.Favorite
                                        Tab.FEED -> Icons.Filled.Email
                                        Tab.PEOPLE -> Icons.Filled.Person
                                        Tab.SETTINGS -> Icons.Filled.Settings
                                    },
                                    contentDescription = null,
                                )
                            }
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (Tab.entries[tab]) {
            Tab.CHAN -> com.meshie4LoRa.ui.stats.StatsScreen(statsVm, characterVm, memoryVm, vm::openPerson, modifier, onTour = vm::startTutorial)
            Tab.FEED -> FeedScreen(vm, modifier)
            Tab.PEOPLE -> PeopleScreen(vm, memoryVm, modifier)
            Tab.SETTINGS -> SettingsScreen(vm, modelsVm, characterVm, memoryVm, modifier)
        }
    }
}

private enum class SettingsSection(val label: String) { MODELS("Models"), CONTEXT("Context"), RADIO("Radio") }

@Composable
private fun SettingsScreen(
    vm: MainViewModel, modelsVm: ModelsViewModel, characterVm: CharacterViewModel, memoryVm: MemoryViewModel, modifier: Modifier,
) {
    var section by rememberSaveable { mutableIntStateOf(SettingsSection.MODELS.ordinal) }
    Column(modifier.fillMaxSize()) {
        androidx.compose.material3.PrimaryTabRow(selectedTabIndex = section) {
            SettingsSection.entries.forEach { s ->
                androidx.compose.material3.Tab(selected = section == s.ordinal, onClick = { section = s.ordinal }, text = { Text(s.label) })
            }
        }
        val inner = Modifier.weight(1f)
        when (SettingsSection.entries[section]) {
            SettingsSection.MODELS -> ModelsScreen(modelsVm, inner)
            SettingsSection.CONTEXT -> ContextSettings(characterVm, inner)
            SettingsSection.RADIO -> RadioScreen(vm, inner)
        }
    }
}
