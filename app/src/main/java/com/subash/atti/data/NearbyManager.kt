package com.subash.atti.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
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
import com.subash.atti.data.model.ImageAttachment
import com.subash.atti.data.model.Message
import com.subash.atti.data.model.NearbyEvent
import com.subash.atti.data.model.Peer
import com.subash.atti.data.model.PeerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Talks to Google Nearby Connections: announces this phone, finds other ATTI
 * phones, connects, and sends and receives text and photos.
 *
 * Every phone advertises and discovers at the same time. All callbacks arrive
 * on the main thread, so state here is only touched from the main thread.
 */
class NearbyManager(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
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

    /** Photos live here only for the current chat; the folder is deleted when it ends. */
    private val photosRoot = File(appContext.cacheDir, "chat_photos")
    private var chatPhotosDir = File(photosRoot, "none")

    /** Nearby payload id → the photo message it belongs to, sent or received. */
    private val photoMessages = mutableMapOf<Long, Long>()

    /** Nearby payload id → expected size in bytes, for progress and completeness. */
    private val photoSizes = mutableMapOf<Long, Long>()

    /** Photo streams that arrived before the packet announcing them. */
    private val earlyStreams = mutableMapOf<Long, Payload>()
    private val photoJobs = mutableListOf<Job>()

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

    init {
        // Leftovers from a session that was killed mid-chat.
        scope.launch(Dispatchers.IO) { photosRoot.deleteRecursively() }
    }

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
        clearPhotos()
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
        chatPhotosDir = File(photosRoot, System.nanoTime().toString())
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
                updateMessage(message.id) { it.copy(failed = true) }
            }
    }

    /** Back pressed in the chat: tell the other side, hang up and return to scanning. */
    fun leaveChat() {
        val status = _status.value
        if (status is PeerStatus.Disconnected) {
            // Link is already gone; just close the chat.
            goIdle(event = null)
            return
        }
        if (status !is PeerStatus.Connected) return
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

    /** The link dropped mid-chat: keep the messages on screen but stop sending. */
    private fun endChat(peer: Peer, reason: PeerStatus.Disconnected.Reason) {
        fakeReplyJob?.cancel()
        // Photos still on their way won't arrive now.
        _messages.update { list ->
            list.map { if (it.image?.progress != null) it.copy(failed = true) else it }
        }
        _status.value = PeerStatus.Disconnected(peer, reason)
    }

    private fun receiveMessage(text: String) {
        // Use our own clock: the two phones' clocks may not agree.
        _messages.update { it + Message(nextMessageId++, text, System.currentTimeMillis(), fromMe = false) }
    }

    /** A request or chat ended: go back to being visible and scanning. */
    private fun goIdle(event: NearbyEvent?) {
        requestTimeoutJob?.cancel()
        fakeReplyJob?.cancel()
        clearPhotos()
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
                // No Bye first, so they walked away, switched off Bluetooth or closed the app.
                is PeerStatus.Connected ->
                    if (status.peer.endpointId == endpointId) {
                        endChat(status.peer, PeerStatus.Disconnected.Reason.OUT_OF_RANGE)
                    }
                else -> Unit
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> payload.asBytes()?.let(Packet::decode)?.let { onPacket(endpointId, it) }
                Payload.Type.STREAM -> onPhotoStream(endpointId, payload)
                else -> client.cancelPayload(payload.id)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) =
            onPhotoProgress(update)
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

            is Packet.Image -> if (status is PeerStatus.Connected && status.peer.endpointId == endpointId) {
                onPhotoAnnounced(packet)
            }

            Packet.Bye -> if (status is PeerStatus.Connected && status.peer.endpointId == endpointId) {
                client.disconnectFromEndpoint(endpointId)
                endChat(status.peer, PeerStatus.Disconnected.Reason.LEFT)
            }
        }
    }

    private fun send(endpointId: String, packet: Packet) =
        client.sendPayload(endpointId, Payload.fromBytes(Packet.encode(packet)))

    // endregion

    // region Photos
    //
    // A photo travels as two payloads: a small Packet.Image announcing it (size and
    // shape, so the bubble can appear straight away), then the JPEG as a stream.
    // Either may arrive first.

    /** Shrinks the picked photo and sends it to the open chat. */
    fun sendImage(uri: Uri) {
        val peer = (_status.value as? PeerStatus.Connected)?.peer ?: return
        val out = File(chatPhotosDir, "out-${System.nanoTime()}.jpg")
        photoJobs += scope.launch {
            val photo = withContext(Dispatchers.IO) { ImageCompressor.compress(appContext, uri, out) }
            if (photo == null) {
                _events.tryEmit(NearbyEvent.PhotoUnreadable)
                return@launch
            }
            // The chat may have ended while we were compressing.
            if ((_status.value as? PeerStatus.Connected)?.peer != peer) return@launch

            val size = photo.file.length()
            val message = Message(
                nextMessageId++, text = "", System.currentTimeMillis(), fromMe = true,
                image = ImageAttachment(photo.file, photo.width, photo.height, progress = 0f),
            )
            _messages.update { it + message }

            if (FakePeers.isFake(peer.endpointId)) {
                fakePhotoReply(message)
                return@launch
            }
            val payload = Payload.fromStream(ParcelFileDescriptor.open(photo.file, ParcelFileDescriptor.MODE_READ_ONLY))
            photoMessages[payload.id] = message.id
            photoSizes[payload.id] = size
            send(peer.endpointId, Packet.Image(payload.id, size, photo.width, photo.height, message.timestamp))
            client.sendPayload(peer.endpointId, payload).addOnFailureListener { e ->
                Log.w(TAG, "sending photo failed", e)
                updateMessage(message.id) { it.copy(failed = true) }
            }
        }
    }

    private fun onPhotoAnnounced(packet: Packet.Image) {
        if (packet.size !in 1..Packet.Image.MAX_SIZE || packet.w <= 0 || packet.h <= 0) return
        val message = Message(
            nextMessageId++, text = "", System.currentTimeMillis(), fromMe = false,
            image = ImageAttachment(file = null, packet.w, packet.h, progress = 0f),
        )
        _messages.update { it + message }
        photoMessages[packet.payloadId] = message.id
        photoSizes[packet.payloadId] = packet.size
        earlyStreams.remove(packet.payloadId)?.let(::readPhoto)
    }

    private fun onPhotoStream(endpointId: String, payload: Payload) {
        val status = _status.value
        if (status !is PeerStatus.Connected || status.peer.endpointId != endpointId) {
            client.cancelPayload(payload.id)
            return
        }
        if (payload.id in photoMessages) readPhoto(payload) else earlyStreams[payload.id] = payload
    }

    /** Copies an incoming photo stream to a file; Nearby feeds it as bytes arrive. */
    private fun readPhoto(payload: Payload) {
        val messageId = photoMessages[payload.id] ?: return
        val expected = photoSizes[payload.id] ?: return
        val file = File(chatPhotosDir, "in-${payload.id}.jpg")
        photoJobs += scope.launch {
            val complete = withContext(Dispatchers.IO) {
                runCatching {
                    file.parentFile?.mkdirs()
                    val input = payload.asStream()?.asInputStream() ?: return@runCatching false
                    input.use { copyLimited(it, file, limit = expected) } == expected
                }.getOrDefault(false)
            }
            if (complete) {
                updatePhoto(messageId) { it.copy(file = file, progress = null) }
            } else {
                file.delete()
                updateMessage(messageId) { it.copy(failed = true) }
            }
        }
    }

    private fun onPhotoProgress(update: PayloadTransferUpdate) {
        val messageId = photoMessages[update.payloadId] ?: return
        when (update.status) {
            PayloadTransferUpdate.Status.IN_PROGRESS -> {
                val total = photoSizes[update.payloadId]?.takeIf { it > 0 } ?: return
                val fraction = (update.bytesTransferred / total.toFloat()).coerceIn(0f, 1f)
                updatePhoto(messageId) { if (it.progress != null) it.copy(progress = fraction) else it }
            }
            // For photos we receive, readPhoto() marks them complete once the file is written.
            PayloadTransferUpdate.Status.SUCCESS ->
                if (_messages.value.any { it.id == messageId && it.fromMe }) {
                    updatePhoto(messageId) { it.copy(progress = null) }
                }
            PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED ->
                updateMessage(messageId) { it.copy(failed = true) }
        }
    }

    /** Ends this chat's photo transfers and deletes its photos. */
    private fun clearPhotos() {
        photoJobs.forEach { it.cancel() }
        photoJobs.clear()
        earlyStreams.values.forEach { runCatching { it.asStream()?.asInputStream()?.close() } }
        earlyStreams.clear()
        photoMessages.clear()
        photoSizes.clear()
        val dir = chatPhotosDir
        scope.launch(Dispatchers.IO) { dir.deleteRecursively() }
    }

    private fun updateMessage(id: Long, change: (Message) -> Message) =
        _messages.update { list -> list.map { if (it.id == id) change(it) else it } }

    private fun updatePhoto(id: Long, change: (ImageAttachment) -> ImageAttachment) =
        updateMessage(id) { message -> message.image?.let { message.copy(image = change(it)) } ?: message }

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
            val status = _status.value as? PeerStatus.Connected ?: return@launch
            when (text) {
                "/bye" -> endChat(status.peer, PeerStatus.Disconnected.Reason.LEFT)
                "/lost" -> endChat(status.peer, PeerStatus.Disconnected.Reason.OUT_OF_RANGE)
                else -> receiveMessage("You said: $text")
            }
        }
    }

    /** Test Bot "receives" the photo with a progress ring, then sends the same one back. */
    private fun fakePhotoReply(sent: Message) {
        val photo = sent.image ?: return
        photoJobs += scope.launch {
            for (step in 1..FAKE_PHOTO_STEPS) {
                delay(FAKE_PHOTO_STEP_MS)
                updatePhoto(sent.id) { it.copy(progress = step / FAKE_PHOTO_STEPS.toFloat()) }
            }
            updatePhoto(sent.id) { it.copy(progress = null) }

            delay(FAKE_REPLY_DELAY_MS / 2)
            if (_status.value !is PeerStatus.Connected) return@launch
            val reply = Message(
                nextMessageId++, text = "", System.currentTimeMillis(), fromMe = false,
                image = photo.copy(file = null, progress = 0f),
            )
            _messages.update { it + reply }
            for (step in 1..FAKE_PHOTO_STEPS) {
                delay(FAKE_PHOTO_STEP_MS)
                updatePhoto(reply.id) { it.copy(progress = step / FAKE_PHOTO_STEPS.toFloat()) }
            }
            updatePhoto(reply.id) { it.copy(file = photo.file, progress = null) }
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
        private const val FAKE_PHOTO_STEPS = 10
        private const val FAKE_PHOTO_STEP_MS = 150L

        /** Give up if the other person hasn't answered by then. */
        private const val REQUEST_TIMEOUT_MS = 45_000L

        /** Time for the Busy packet to arrive before we disconnect. */
        private const val BUSY_HANGUP_DELAY_MS = 1_000L

        /** Time for the Bye packet to arrive before we disconnect. */
        private const val BYE_HANGUP_DELAY_MS = 500L
    }
}
