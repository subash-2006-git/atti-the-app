package com.subash.atti.data

import android.content.Context
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.subash.atti.BuildConfig
import com.subash.atti.data.model.Message
import com.subash.atti.data.model.NearbyEvent
import com.subash.atti.data.model.Peer
import com.subash.atti.data.model.PeerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Talks to Google Nearby Connections: announces this phone, finds other ATTI
 * phones, and (in later steps) connects, sends and receives.
 *
 * Every phone advertises and discovers at the same time. All callbacks arrive
 * on the main thread, so state here is only touched from the main thread.
 */
class NearbyManager(context: Context, private val scope: CoroutineScope) {

    private val client = Nearby.getConnectionsClient(context)
    private var myName = ""
    private var started = false
    private var advertising = false
    private var discovering = false
    private var fakePeersJob: Job? = null
    private var requestTimeoutJob: Job? = null
    private var fakeReplyJob: Job? = null

    /** Requests we accepted only to answer "busy" before hanging up. */
    private val busyEndpoints = mutableSetOf<String>()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _status = MutableStateFlow<PeerStatus>(PeerStatus.Idle)
    val status: StateFlow<PeerStatus> = _status.asStateFlow()

    /** The open chat's messages. Kept in memory only, so they vanish when the chat ends. */
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()
    private var nextMessageId = 0L

    /** Someone asking to chat with us, waiting for Accept or Decline. */
    private val _incoming = MutableStateFlow<Peer?>(null)
    val incoming: StateFlow<Peer?> = _incoming.asStateFlow()

    private val _events = MutableSharedFlow<NearbyEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<NearbyEvent> = _events.asSharedFlow()

    /** Starts advertising [name] and looking for others. Safe to call repeatedly. */
    fun start(name: String) {
        myName = name
        started = true
        startAdvertising()
        startDiscovery()
    }

    /** Stops looking and clears the list, then looks again. */
    fun rescan() {
        stopDiscovery()
        _peers.value = emptyList()
        startDiscovery()
    }

    /** App went to the background: stop the radios unless a chat is in progress. */
    fun pause() {
        if (_status.value != PeerStatus.Idle) return
        stopDiscovery()
        stopAdvertising()
    }

    /** App came back to the foreground. */
    fun resume() {
        if (!started || _status.value != PeerStatus.Idle) return
        startAdvertising()
        startDiscovery()
    }

    /** Shuts everything down; used when the app is closed. */
    fun stop() {
        started = false
        stopDiscovery()
        stopAdvertising()
        client.stopAllEndpoints()
        requestTimeoutJob?.cancel()
        fakeReplyJob?.cancel()
        busyEndpoints.clear()
        _peers.value = emptyList()
        _incoming.value = null
        _status.value = PeerStatus.Idle
    }

    // region Advertising and discovery

    private fun startAdvertising() {
        if (advertising) return
        advertising = true
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        client.startAdvertising(myName, SERVICE_ID, connectionCallback, options)
            .addOnFailureListener { e ->
                if (e.statusCode() != ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) {
                    advertising = false
                    report("Couldn't make this phone visible", e)
                }
            }
    }

    private fun stopAdvertising() {
        if (!advertising) return
        advertising = false
        client.stopAdvertising()
    }

    private fun startDiscovery() {
        if (discovering) return
        discovering = true
        _scanning.value = true
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        client.startDiscovery(SERVICE_ID, discoveryCallback, options)
            .addOnFailureListener { e ->
                if (e.statusCode() != ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) {
                    discovering = false
                    _scanning.value = false
                    report("Couldn't start looking for people", e)
                }
            }
        if (BuildConfig.DEBUG) {
            fakePeersJob?.cancel()
            fakePeersJob = scope.launch {
                delay(FAKE_PEER_DELAY_MS)
                FakePeers.all.forEach(::addPeer)
            }
        }
    }

    private fun stopDiscovery() {
        fakePeersJob?.cancel()
        if (!discovering) return
        discovering = false
        _scanning.value = false
        client.stopDiscovery()
    }

    private fun addPeer(peer: Peer) {
        _peers.update { list -> list.filterNot { it.endpointId == peer.endpointId } + peer }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId == SERVICE_ID) addPeer(Peer(endpointId, info.endpointName))
        }

        override fun onEndpointLost(endpointId: String) {
            _peers.update { list -> list.filterNot { it.endpointId == endpointId } }
        }
    }

    // endregion

    // region Requests and chat

    /** Asks [peer] to chat. The answer arrives as a status change or an event. */
    fun requestChat(peer: Peer) {
        if (_status.value != PeerStatus.Idle) return
        _status.value = PeerStatus.Requesting(peer)
        // Nearby is more reliable when it isn't discovering during a connection.
        stopDiscovery()
        requestTimeoutJob = scope.launch {
            delay(REQUEST_TIMEOUT_MS)
            if (isRequesting(peer.endpointId)) {
                hangUp(peer.endpointId)
                goIdle(NearbyEvent.NoAnswer(peer))
            }
        }

        if (FakePeers.isFake(peer.endpointId)) {
            fakeAnswer(peer)
            return
        }
        client.requestConnection(myName, peer.endpointId, connectionCallback)
            .addOnFailureListener { e ->
                Log.w(TAG, "requestConnection failed", e)
                if (isRequesting(peer.endpointId)) goIdle(NearbyEvent.Failed(peer))
            }
    }

    fun cancelRequest() {
        val status = _status.value as? PeerStatus.Requesting ?: return
        hangUp(status.peer.endpointId)
        goIdle(event = null)
    }

    fun acceptIncoming() {
        val peer = _incoming.value ?: return
        _incoming.value = null
        if (_status.value != PeerStatus.Idle) {
            declineEndpoint(peer.endpointId)
            return
        }
        _status.value = PeerStatus.Accepting(peer)
        stopDiscovery()
        if (FakePeers.isFake(peer.endpointId)) {
            openChat(peer)
            return
        }
        client.acceptConnection(peer.endpointId, payloadCallback)
            .addOnFailureListener { e ->
                Log.w(TAG, "acceptConnection failed", e)
                if ((_status.value as? PeerStatus.Accepting)?.peer == peer) goIdle(NearbyEvent.Failed(peer))
            }
    }

    fun declineIncoming() {
        val peer = _incoming.value ?: return
        _incoming.value = null
        declineEndpoint(peer.endpointId)
    }

    /** Debug builds only: pretend Test Bot is asking to chat. */
    fun simulateIncomingRequest() {
        if (BuildConfig.DEBUG && _status.value == PeerStatus.Idle && _incoming.value == null) {
            _incoming.value = FakePeers.TestBot
        }
    }

    private fun isRequesting(endpointId: String) =
        (_status.value as? PeerStatus.Requesting)?.peer?.endpointId == endpointId

    private fun openChat(peer: Peer) {
        requestTimeoutJob?.cancel()
        _messages.value = emptyList()
        _status.value = PeerStatus.Connected(peer)
    }

    fun sendMessage(text: String) {
        val status = _status.value as? PeerStatus.Connected ?: return
        val clean = text.trim().take(Message.MAX_LENGTH)
        if (clean.isEmpty()) return
        val message = Message(nextMessageId++, clean, System.currentTimeMillis(), fromMe = true)
        _messages.update { it + message }

        val endpointId = status.peer.endpointId
        if (FakePeers.isFake(endpointId)) {
            fakeChatReply(clean)
            return
        }
        send(endpointId, Packet.Text(clean, message.timestamp))
            .addOnFailureListener { e ->
                Log.w(TAG, "sendPayload failed", e)
                _messages.update { list -> list.map { if (it.id == message.id) it.copy(failed = true) else it } }
            }
    }

    /** Back pressed in the chat: tell the other side, hang up and return to scanning. */
    fun leaveChat() {
        val status = _status.value as? PeerStatus.Connected ?: return
        val endpointId = status.peer.endpointId
        if (!FakePeers.isFake(endpointId)) {
            send(endpointId, Packet.Bye).addOnCompleteListener {
                scope.launch {
                    delay(BYE_HANGUP_DELAY_MS)
                    client.disconnectFromEndpoint(endpointId)
                }
            }
        }
        goIdle(event = null)
    }

    private fun receiveMessage(text: String) {
        // Use our own clock: the two phones' clocks may not agree.
        _messages.update { it + Message(nextMessageId++, text, System.currentTimeMillis(), fromMe = false) }
    }

    /** A request or chat ended: go back to being visible and scanning. */
    private fun goIdle(event: NearbyEvent?) {
        requestTimeoutJob?.cancel()
        fakeReplyJob?.cancel()
        _messages.value = emptyList()
        _status.value = PeerStatus.Idle
        if (started) {
            startAdvertising()
            startDiscovery()
        }
        event?.let(_events::tryEmit)
    }

    private fun declineEndpoint(endpointId: String) {
        if (!FakePeers.isFake(endpointId)) client.rejectConnection(endpointId)
    }

    private fun hangUp(endpointId: String) {
        if (!FakePeers.isFake(endpointId)) client.disconnectFromEndpoint(endpointId)
    }

    // endregion

    // region Nearby callbacks

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Our own outgoing request: Nearby needs both sides to accept.
            if (!info.isIncomingConnection) {
                client.acceptConnection(endpointId, payloadCallback)
                return
            }
            val busy = _status.value != PeerStatus.Idle || _incoming.value != null
            if (busy) {
                // Accept just long enough to say "busy", so they don't see "declined".
                busyEndpoints += endpointId
                client.acceptConnection(endpointId, payloadCallback)
            } else {
                _incoming.value = Peer(endpointId, info.endpointName)
            }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val code = resolution.status.statusCode
            val ok = code == ConnectionsStatusCodes.STATUS_OK

            if (endpointId in busyEndpoints) {
                if (ok) {
                    send(endpointId, Packet.Busy)
                    scope.launch {
                        delay(BUSY_HANGUP_DELAY_MS)
                        client.disconnectFromEndpoint(endpointId)
                    }
                }
                busyEndpoints -= endpointId
                return
            }
            if (!ok && _incoming.value?.endpointId == endpointId) {
                // They cancelled before we answered.
                _incoming.value = null
                return
            }

            when (val status = _status.value) {
                is PeerStatus.Requesting -> if (status.peer.endpointId == endpointId) {
                    // On success, wait for their Hi or Busy packet.
                    when (code) {
                        ConnectionsStatusCodes.STATUS_OK -> Unit
                        ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED ->
                            goIdle(NearbyEvent.Declined(status.peer))
                        else -> goIdle(NearbyEvent.Failed(status.peer))
                    }
                }

                is PeerStatus.Accepting -> if (status.peer.endpointId == endpointId) {
                    if (ok) {
                        send(endpointId, Packet.Hi)
                        openChat(status.peer)
                    } else {
                        goIdle(NearbyEvent.Failed(status.peer))
                    }
                }

                else -> Unit
            }
        }

        override fun onDisconnected(endpointId: String) {
            busyEndpoints -= endpointId
            if (_incoming.value?.endpointId == endpointId) _incoming.value = null
            when (val status = _status.value) {
                is PeerStatus.Requesting ->
                    if (status.peer.endpointId == endpointId) goIdle(NearbyEvent.Failed(status.peer))
                is PeerStatus.Accepting ->
                    if (status.peer.endpointId == endpointId) goIdle(NearbyEvent.Failed(status.peer))
                else -> Unit
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val packet = payload.asBytes()?.let(Packet::decode) ?: return
            onPacket(endpointId, packet)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private fun onPacket(endpointId: String, packet: Packet) {
        val status = _status.value
        when (packet) {
            Packet.Hi -> if (status is PeerStatus.Requesting && status.peer.endpointId == endpointId) {
                openChat(status.peer)
            }

            Packet.Busy -> if (status is PeerStatus.Requesting && status.peer.endpointId == endpointId) {
                client.disconnectFromEndpoint(endpointId)
                goIdle(NearbyEvent.Busy(status.peer))
            }

            is Packet.Text -> if (status is PeerStatus.Connected && status.peer.endpointId == endpointId) {
                receiveMessage(packet.text)
            }

            Packet.Bye -> Unit
        }
    }

    private fun send(endpointId: String, packet: Packet) =
        client.sendPayload(endpointId, Payload.fromBytes(Packet.encode(packet)))

    // endregion

    // region Fake peers (debug builds)

    private fun fakeAnswer(peer: Peer) {
        fakeReplyJob = scope.launch {
            delay(FAKE_REPLY_DELAY_MS)
            if (!isRequesting(peer.endpointId)) return@launch
            when (peer) {
                FakePeers.ShyBot -> goIdle(NearbyEvent.Declined(peer))
                FakePeers.BusyBot -> goIdle(NearbyEvent.Busy(peer))
                else -> openChat(peer)
            }
        }
    }

    private fun fakeChatReply(text: String) {
        fakeReplyJob = scope.launch {
            delay(FAKE_REPLY_DELAY_MS / 2)
            if (_status.value !is PeerStatus.Connected) return@launch
            receiveMessage("You said: $text")
        }
    }

    // endregion

    private fun report(message: String, e: Exception) {
        Log.w(TAG, message, e)
        _events.tryEmit(NearbyEvent.Error(message))
    }

    private fun Exception.statusCode(): Int? = (this as? ApiException)?.statusCode

    companion object {
        private const val TAG = "NearbyManager"

        /** Only phones using this ID can see each other, so only ATTI users show up. */
        const val SERVICE_ID = "com.subash.atti"

        /** Allows many links, so a busy phone can still answer "busy" to a new request. */
        private val STRATEGY = Strategy.P2P_CLUSTER

        private const val FAKE_PEER_DELAY_MS = 1500L
        private const val FAKE_REPLY_DELAY_MS = 1500L

        /** Give up if the other person hasn't answered by then. */
        private const val REQUEST_TIMEOUT_MS = 45_000L

        /** Time for the Busy packet to arrive before we disconnect. */
        private const val BUSY_HANGUP_DELAY_MS = 1_000L

        /** Time for the Bye packet to arrive before we disconnect. */
        private const val BYE_HANGUP_DELAY_MS = 500L
    }
}
