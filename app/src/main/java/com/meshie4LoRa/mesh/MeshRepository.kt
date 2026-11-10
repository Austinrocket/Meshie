package com.meshie4LoRa.mesh

import android.util.Log
import com.meshie4LoRa.data.AppDatabase
import com.meshie4LoRa.data.BROADCAST_NODE
import com.meshie4LoRa.data.MessageEntity
import com.meshie4LoRa.data.MessageOrigin
import com.meshie4LoRa.data.NodeEntity
import com.meshie4LoRa.data.PositionEntity
import com.meshie4LoRa.data.SettingsStore
import com.meshie4LoRa.radio.BleRadioTransport
import com.meshie4LoRa.radio.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import org.meshtastic.proto.AdminProtos
import org.meshtastic.proto.ChannelProtos
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.MeshProtos.FromRadio.PayloadVariantCase
import org.meshtastic.proto.Portnums.PortNum
import org.meshtastic.proto.TelemetryProtos
import com.google.protobuf.ByteString
import java.io.IOException
import kotlin.random.Random

private const val TAG = "MeshChan.Mesh"
const val MAX_LONG_NAME_BYTES = 39
const val MAX_SHORT_NAME_BYTES = 4
private const val MAX_CONFIG_RETRIES = 2
private const val POSITION_HISTORY_MS = 3 * 60 * 60 * 1000L

data class ChannelInfo(val index: Int, val settingsName: String, val role: ChannelProtos.Channel.Role, val presetName: String) {
    val name: String
        get() = settingsName.ifBlank { if (role == ChannelProtos.Channel.Role.PRIMARY) presetName else "Channel $index" }
}

/** Turns FromRadio packets into persisted mesh state and sends outgoing text. */
class MeshRepository(
    private val transport: BleRadioTransport,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
) {
    private val _myNodeNum = MutableStateFlow<Long?>(null)
    val myNodeNum: StateFlow<Long?> = _myNodeNum

    private val _channels = MutableStateFlow<List<ChannelInfo>>(emptyList())
    val channels: StateFlow<List<ChannelInfo>> = _channels

    private val _configComplete = MutableStateFlow(false)
    val configComplete: StateFlow<Boolean> = _configComplete

    private val _incoming = MutableSharedFlow<MessageEntity>(extraBufferCapacity = 64)
    /** Text messages from other nodes, as stored (with their DB id). */
    val incoming: SharedFlow<MessageEntity> = _incoming

    private var modemPresetName: String = "LongFast"
    private var configNonce = 0
    private var configRetries = 0
    private var myUser: MeshProtos.User? = null

    val nodes = db.nodes().observeAll()
    /** The radio's current node name; the Bluetooth name saved at pairing (e.g. "ABCD_1234") goes stale after a rename. */
    val radioLabel = kotlinx.coroutines.flow.combine(myNodeNum, nodes, settings.deviceName) { me, all, bleName ->
        all.firstOrNull { it.nodeNum == me }?.let { n -> n.longName?.let { l -> l + (n.shortName?.let { " ($it)" } ?: "") } } ?: bleName
    }
    val unreadByNode = db.messages().observeUnreadByNode()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val dmPeers = myNodeNum.flatMapLatest { db.messages().observeDmPeers(it ?: -1L) }
    fun recentMessages(limit: Int = 200) = db.messages().observeRecent(limit)
    suspend fun markRead(nodeNum: Long) = db.messages().markRead(nodeNum)

    /** Debug builds only: pretend a text message arrived from the mesh. */
    suspend fun simulateIncoming(m: MessageEntity) {
        _incoming.emit(m.copy(id = db.messages().insert(m)))
    }

    init {
        // Remembered from the last connection, so we know who "we" are while the radio is away.
        scope.launch { settings.myNodeNum.first()?.let { _myNodeNum.compareAndSet(null, it) } }
        scope.launch {
            transport.fromRadio.collect { bytes ->
                try {
                    handle(MeshProtos.FromRadio.parseFrom(bytes))
                } catch (e: Exception) {
                    Log.w(TAG, "Bad FromRadio packet", e)
                }
            }
        }
        scope.launch {
            transport.state.collect { state ->
                when (state) {
                    LinkState.CONNECTED -> requestConfig()
                    LinkState.DISCONNECTED -> _configComplete.value = false
                    else -> Unit
                }
            }
        }
    }

    private suspend fun requestConfig() {
        _configComplete.value = false
        configNonce = Random.nextInt(1, Int.MAX_VALUE)
        val msg = MeshProtos.ToRadio.newBuilder().setWantConfigId(configNonce).build()
        runCatching { transport.send(msg.toByteArray()) }.onFailure { Log.w(TAG, "want_config failed", it) }
    }

    suspend fun sendText(text: String, channel: Int, to: Long = BROADCAST_NODE, origin: MessageOrigin = MessageOrigin.BOT_MANUAL) {
        val packetId = Random.nextInt(1, Int.MAX_VALUE).toLong()
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setTo(to.toInt())
            .setChannel(channel)
            .setId(packetId.toInt())
            .setWantAck(to != BROADCAST_NODE)
            .setDecoded(
                MeshProtos.Data.newBuilder()
                    .setPortnum(PortNum.TEXT_MESSAGE_APP)
                    .setPayload(ByteString.copyFromUtf8(text))
            )
            .build()
        transport.send(MeshProtos.ToRadio.newBuilder().setPacket(packet).build().toByteArray())
        db.messages().insert(
            MessageEntity(
                packetId = packetId,
                channel = channel,
                fromNode = _myNodeNum.value ?: 0L,
                toNode = to,
                text = text,
                rxTime = System.currentTimeMillis(),
                origin = origin,
            )
        )
    }

    suspend fun setOwner(longName: String, shortName: String) {
        val me = _myNodeNum.value ?: throw IOException("Radio not ready")
        require(longName.isNotBlank() && longName.toByteArray().size <= MAX_LONG_NAME_BYTES) { "Long name must be 1-$MAX_LONG_NAME_BYTES bytes" }
        require(shortName.isNotBlank() && shortName.toByteArray().size <= MAX_SHORT_NAME_BYTES) { "Short name must be 1-$MAX_SHORT_NAME_BYTES bytes" }
        val admin = AdminProtos.AdminMessage.newBuilder()
            .setSetOwner(MeshProtos.User.newBuilder().setLongName(longName).setShortName(shortName))
            .build()
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setTo(me.toInt())
            .setId(Random.nextInt(1, Int.MAX_VALUE))
            .setWantAck(true)
            .setDecoded(MeshProtos.Data.newBuilder().setPortnum(PortNum.ADMIN_APP).setPayload(admin.toByteString()))
            .build()
        transport.send(MeshProtos.ToRadio.newBuilder().setPacket(packet).build().toByteArray())
        db.nodes().get(me)?.let { db.nodes().upsert(it.copy(longName = longName, shortName = shortName)) }
        myUser = myUser?.toBuilder()?.setLongName(longName)?.setShortName(shortName)?.build()
        Log.i(TAG, "Owner set to '$longName' ($shortName)")
        delay(1_000)
        announceNodeInfo()
    }

    /** Broadcast our NodeInfo so other nodes learn our (new) name right away instead of on the firmware's slow timer. */
    suspend fun announceNodeInfo() {
        val user = myUser ?: throw IOException("Own node info not loaded yet")
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setTo(BROADCAST_NODE.toInt())
            .setId(Random.nextInt(1, Int.MAX_VALUE))
            .setDecoded(MeshProtos.Data.newBuilder().setPortnum(PortNum.NODEINFO_APP).setPayload(user.toByteString()))
            .build()
        transport.send(MeshProtos.ToRadio.newBuilder().setPacket(packet).build().toByteArray())
        Log.i(TAG, "Announced NodeInfo '${user.longName}' (public key ${user.publicKey.size()} bytes)")
    }

    private suspend fun handle(msg: MeshProtos.FromRadio) {
        when (msg.payloadVariantCase) {
            PayloadVariantCase.MY_INFO -> {
                val num = msg.myInfo.myNodeNum.toUnsignedLong()
                _myNodeNum.value = num
                settings.setMyNodeNum(num)
            }
            PayloadVariantCase.NODE_INFO -> handleNodeInfo(msg.nodeInfo)
            PayloadVariantCase.CHANNEL -> handleChannel(msg.channel)
            PayloadVariantCase.CONFIG -> if (msg.config.hasLora()) {
                modemPresetName = presetDisplayName(msg.config.lora.modemPreset.name)
                _channels.value = _channels.value.map { it.copy(presetName = modemPresetName) }
            }
            PayloadVariantCase.CONFIG_COMPLETE_ID -> if (msg.configCompleteId == configNonce) {
                Log.i(TAG, "Config complete: node ${_myNodeNum.value}, ${_channels.value.size} channels")
                // A radio still streaming to an earlier connection can finish without sending channels; ask again.
                if (_channels.value.isEmpty() && configRetries < MAX_CONFIG_RETRIES) {
                    configRetries++
                    Log.w(TAG, "No channels in config, asking again ($configRetries/$MAX_CONFIG_RETRIES)")
                    requestConfig()
                } else {
                    configRetries = 0
                    _configComplete.value = true
                }
            }
            PayloadVariantCase.PACKET -> handlePacket(msg.packet)
            PayloadVariantCase.REBOOTED -> requestConfig()
            else -> Unit
        }
    }

    private fun handleChannel(ch: ChannelProtos.Channel) {
        if (ch.role == ChannelProtos.Channel.Role.DISABLED) {
            _channels.value = _channels.value.filter { it.index != ch.index }
            return
        }
        val info = ChannelInfo(ch.index, ch.settings.name, ch.role, modemPresetName)
        _channels.value = (_channels.value.filter { it.index != ch.index } + info).sortedBy { it.index }
    }

    private suspend fun handleNodeInfo(info: MeshProtos.NodeInfo) {
        val num = info.num.toUnsignedLong()
        if (num == _myNodeNum.value && info.hasUser()) myUser = info.user
        val now = System.currentTimeMillis()
        val existing = db.nodes().get(num)
        var node = existing ?: NodeEntity(nodeNum = num, firstSeen = now, lastHeard = 0)
        if (info.hasUser()) node = node.withUser(info.user)
        if (info.hasPosition()) node = node.withPosition(info.position)
        if (info.hasDeviceMetrics()) node = node.withDeviceMetrics(info.deviceMetrics)
        val lastHeard = info.lastHeard.toUnsignedLong() * 1000
        node = node.copy(
            lastHeard = maxOf(node.lastHeard, lastHeard),
            snr = info.snr.takeIf { it != 0f } ?: node.snr,
            hopsAway = if (info.hasHopsAway()) info.hopsAway else node.hopsAway,
            viaMqtt = info.viaMqtt,
        )
        db.nodes().upsert(node)
        recordPosition(existing, node)
    }

    private suspend fun recordPosition(old: NodeEntity?, new: NodeEntity) {
        if (!new.hasPosition || new.positionTime == old?.positionTime) return
        val at = new.positionTime ?: System.currentTimeMillis()
        db.positions().insert(PositionEntity(nodeNum = new.nodeNum, latitude = new.latitude!!, longitude = new.longitude!!, at = at))
        db.positions().prune(System.currentTimeMillis() - POSITION_HISTORY_MS)
    }

    private suspend fun handlePacket(p: MeshProtos.MeshPacket) {
        if (!p.hasDecoded()) return
        val from = p.from.toUnsignedLong()
        val now = System.currentTimeMillis()
        val hops = if (p.hopStart > 0) p.hopStart - p.hopLimit else null
        val rssi = if (p.hasRxRssi() && p.rxRssi != 0) p.rxRssi else null
        val snr = p.rxSnr.takeIf { it != 0f }

        var node = db.nodes().get(from) ?: NodeEntity(nodeNum = from, firstSeen = now, lastHeard = now)
        val before = node
        node = node.copy(lastHeard = now, snr = snr ?: node.snr, rssi = rssi ?: node.rssi, hopsAway = hops ?: node.hopsAway, viaMqtt = p.viaMqtt)

        val payload = p.decoded.payload
        var received: MessageEntity? = null
        when (p.decoded.portnum) {
            PortNum.TEXT_MESSAGE_APP -> {
                if (from != _myNodeNum.value && db.messages().countByPacket(p.id.toUnsignedLong(), from) == 0) {
                    val m = MessageEntity(
                        packetId = p.id.toUnsignedLong(),
                        channel = p.channel,
                        fromNode = from,
                        toNode = p.to.toUnsignedLong(),
                        text = payload.toStringUtf8(),
                        rxTime = if (p.hasRxTime() && p.rxTime != 0) p.rxTime.toUnsignedLong() * 1000 else now,
                        rxSnr = snr,
                        rxRssi = rssi,
                        hopsAway = hops,
                        viaMqtt = p.viaMqtt,
                        origin = MessageOrigin.MESH,
                        read = p.to.toUnsignedLong() == BROADCAST_NODE,
                    )
                    received = m.copy(id = db.messages().insert(m))
                }
            }
            PortNum.POSITION_APP -> node = node.withPosition(MeshProtos.Position.parseFrom(payload))
            PortNum.NODEINFO_APP -> node = node.withUser(MeshProtos.User.parseFrom(payload))
            PortNum.TELEMETRY_APP -> {
                val t = TelemetryProtos.Telemetry.parseFrom(payload)
                if (t.hasDeviceMetrics()) node = node.withDeviceMetrics(t.deviceMetrics)
                if (t.hasEnvironmentMetrics()) node = node.withEnvironment(t.environmentMetrics)
            }
            else -> Unit
        }
        db.nodes().upsert(node)
        recordPosition(before, node)
        received?.let { _incoming.emit(it) }
    }
}

private fun Int.toUnsignedLong(): Long = Integer.toUnsignedLong(this)

private fun presetDisplayName(enumName: String): String =
    enumName.split('_').joinToString("") { it.lowercase().replaceFirstChar(Char::uppercase) }

private fun NodeEntity.withUser(u: MeshProtos.User) = copy(
    userId = u.id.ifBlank { userId },
    longName = u.longName.ifBlank { longName },
    shortName = u.shortName.ifBlank { shortName },
    hwModel = u.hwModel.name,
    role = u.role.name,
)

private fun NodeEntity.withPosition(p: MeshProtos.Position): NodeEntity {
    if (!p.hasLatitudeI() || !p.hasLongitudeI() || (p.latitudeI == 0 && p.longitudeI == 0)) return this
    return copy(
        latitude = p.latitudeI * 1e-7,
        longitude = p.longitudeI * 1e-7,
        altitude = if (p.hasAltitude()) p.altitude else altitude,
        positionTime = if (p.time != 0) p.time.toUnsignedLong() * 1000 else System.currentTimeMillis(),
        positionPrecisionBits = p.precisionBits.takeIf { it > 0 },
        groundSpeed = if (p.hasGroundSpeed()) p.groundSpeed else null,
        groundTrack = if (p.hasGroundTrack()) p.groundTrack else null,
    )
}

private fun NodeEntity.withDeviceMetrics(m: TelemetryProtos.DeviceMetrics) = copy(
    batteryLevel = if (m.hasBatteryLevel()) m.batteryLevel else batteryLevel,
    voltage = if (m.hasVoltage()) m.voltage else voltage,
    channelUtil = if (m.hasChannelUtilization()) m.channelUtilization else channelUtil,
    airUtilTx = if (m.hasAirUtilTx()) m.airUtilTx else airUtilTx,
    uptimeSeconds = if (m.hasUptimeSeconds()) m.uptimeSeconds.toUnsignedLong() else uptimeSeconds,
)

private fun NodeEntity.withEnvironment(m: TelemetryProtos.EnvironmentMetrics) = copy(
    temperature = if (m.hasTemperature()) m.temperature else temperature,
    humidity = if (m.hasRelativeHumidity()) m.relativeHumidity else humidity,
    pressure = if (m.hasBarometricPressure()) m.barometricPressure else pressure,
)
