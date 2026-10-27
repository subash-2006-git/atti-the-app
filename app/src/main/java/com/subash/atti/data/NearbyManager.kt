package com.subash.atti.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
import com.subash.atti.data.model.Attachment
import com.subash.atti.data.model.ChatActivity
import com.subash.atti.data.model.FileAttachment
import com.subash.atti.data.model.ImageAttachment
import com.subash.atti.data.model.Message
import com.subash.atti.data.model.NearbyEvent
import com.subash.atti.data.model.Peer
import com.subash.atti.data.model.PeerStatus
import com.subash.atti.data.model.VoiceAttachment
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
import kotlin.math.roundToInt

/**
 * Talks to Google Nearby Connections: announces this phone, finds other ATTI
 * phones, connects, and sends and receives text, photos and voice messages.
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

    /** Photos and voice messages live here only for the current chat; the folder is deleted when it ends. */
    private val filesRoot = File(appContext.cacheDir, "chat_files")
    private var chatFilesDir = File(filesRoot, "none")

    /** Nearby payload id → the photo or voice message it belongs to, sent or received. */
    private val transferMessages = mutableMapOf<Long, Long>()

    /** Nearby payload id → expected size in bytes, for progress and completeness. */
    private val transferSizes = mutableMapOf<Long, Long>()

    /** Attachment streams that arrived before the packet announcing them. */
    private val earlyStreams = mutableMapOf<Long, Payload>()
    private val transferJobs = mutableListOf<Job>()

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

    /** What the other person is doing right now: typing, recording or nothing. */
    private val _peerActivity = MutableStateFlow(ChatActivity.NONE)
    val peerActivity: StateFlow<ChatActivity> = _peerActivity.asStateFlow()
    private var peerActivityTimeout: Job? = null
    private var myActivity = ChatActivity.NONE
    private var myActivitySentAt = 0L

    /** Someone asking to chat with us, waiting for Accept or Decline. */
    private val _incoming = MutableStateFlow<Peer?>(null)
    val incoming: StateFlow<Peer?> = _incoming.asStateFlow()

    private val _events = MutableSharedFlow<NearbyEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<NearbyEvent> = _events.asSharedFlow()

    init {
        // Leftovers from a session that was killed mid-chat or mid-recording.
        scope.launch(Dispatchers.IO) {
            filesRoot.deleteRecursively()
            File(appContext.cacheDir, "recordings").deleteRecursively()
        }
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
        clearAttachments()
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
        chatFilesDir = File(filesRoot, System.nanoTime().toString())
        resetActivity()
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
        resetActivity()
        // Photos and voice messages still on their way won't arrive now.
        _messages.update { list ->
            list.map { if (it.attachment?.progress != null) it.copy(failed = true) else it }
        }
        _status.value = PeerStatus.Disconnected(peer, reason)
    }

    private fun receiveMessage(text: String) {
        setPeerActivity(ChatActivity.NONE)
        // Use our own clock: the two phones' clocks may not agree.
        _messages.update { it + Message(nextMessageId++, text, System.currentTimeMillis(), fromMe = false) }
    }

    /** A request or chat ended: go back to being visible and scanning. */
    private fun goIdle(event: NearbyEvent?) {
        requestTimeoutJob?.cancel()
        fakeReplyJob?.cancel()
        clearAttachments()
        resetActivity()
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
                Payload.Type.STREAM -> onAttachmentStream(endpointId, payload)
                else -> client.cancelPayload(payload.id)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) =
            onTransferProgress(update)
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
                onImageAnnounced(packet)
            }

            is Packet.Voice -> if (status is PeerStatus.Connected && status.peer.endpointId == endpointId) {
                onVoiceAnnounced(packet)
            }

            is Packet.Document -> if (status is PeerStatus.Connected && status.peer.endpointId == endpointId) {
                onFileAnnounced(packet)
            }

            is Packet.Typing -> if (status is PeerStatus.Connected && status.peer.endpointId == endpointId) {
                onPeerActivity(packet.what)
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

    // region Attachments: photos, voice messages and files
    //
    // An attachment travels as two payloads: a small packet announcing it
    // (Packet.Image, Packet.Voice or Packet.Document, so its bubble can appear
    // straight away), then the file as a stream. Either may arrive first.

    /** Shrinks the picked photo and sends it to the open chat. */
    fun sendImage(uri: Uri) {
        val peer = connectedPeer() ?: return
        val out = File(chatFilesDir, "out-${System.nanoTime()}.jpg")
        transferJobs += scope.launch {
            val photo = withContext(Dispatchers.IO) { ImageCompressor.compress(appContext, uri, out) }
            if (photo == null) {
                _events.tryEmit(NearbyEvent.PhotoUnreadable)
                return@launch
            }
            // The chat may have ended while we were compressing.
            if (connectedPeer() != peer) return@launch
            val image = ImageAttachment(photo.file, photo.width, photo.height, progress = 0f)
            sendAttachment(peer, image) { payloadId, size, ts ->
                Packet.Image(payloadId, size, photo.width, photo.height, ts)
            }
        }
    }

    /** Sends a finished voice recording to the open chat. */
    fun sendVoice(recording: VoiceRecorder.Recording) {
        val peer = connectedPeer()
        if (peer == null) {
            recording.file.delete()
            return
        }
        // Keep it with the chat's other files so it's deleted when the chat ends.
        val file = File(chatFilesDir, "out-${System.nanoTime()}.m4a").apply { parentFile?.mkdirs() }
        if (!recording.file.renameTo(file)) {
            recording.file.copyTo(file, overwrite = true)
            recording.file.delete()
        }
        val voice = VoiceAttachment(file, recording.durationMs, recording.waveform, progress = 0f)
        sendAttachment(peer, voice) { payloadId, size, ts ->
            val wave = recording.waveform.map { (it * 100).roundToInt() }
            Packet.Voice(payloadId, size, recording.durationMs, wave, ts)
        }
    }

    /** Copies the picked file into the chat and sends it, if it's within the size limit. */
    fun sendFile(uri: Uri) {
        val peer = connectedPeer() ?: return
        transferJobs += scope.launch {
            val copied = when (val picked = withContext(Dispatchers.IO) { copyPickedFile(uri) }) {
                is PickedFile.Copied -> picked
                PickedFile.TooBig -> {
                    _events.tryEmit(NearbyEvent.FileTooBig)
                    return@launch
                }
                PickedFile.Unreadable -> {
                    _events.tryEmit(NearbyEvent.FileUnreadable)
                    return@launch
                }
            }
            // The chat may have ended while we were copying.
            if (connectedPeer() != peer) return@launch
            val document = FileAttachment(copied.file, copied.name, copied.mime, copied.file.length(), progress = 0f)
            sendAttachment(peer, document) { payloadId, size, ts ->
                Packet.Document(payloadId, size, copied.name, copied.mime, ts)
            }
        }
    }

    private sealed interface PickedFile {
        data class Copied(val file: File, val name: String, val mime: String) : PickedFile
        data object TooBig : PickedFile
        data object Unreadable : PickedFile
    }

    /** Copies a picked document into this chat's folder, so it's deleted when the chat ends. */
    private fun copyPickedFile(uri: Uri): PickedFile {
        val resolver = appContext.contentResolver
        var name = ""
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { row ->
                if (row.moveToFirst()) {
                    row.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !row.isNull(it) }
                        ?.let { name = row.getString(it) }
                    row.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !row.isNull(it) }
                        ?.let { size = row.getLong(it) }
                }
            }
        }
        // Refuse early when the size is known; otherwise the copy below stops at the limit.
        if (size > Packet.Document.MAX_SIZE) return PickedFile.TooBig

        val safeName = FileNames.safe(name)
        val mime = FileNames.safeMime(resolver.getType(uri).orEmpty())
        val out = File(chatFilesDir, "out-${System.nanoTime()}/$safeName").apply { parentFile?.mkdirs() }
        val written = runCatching {
            resolver.openInputStream(uri)?.use { copyLimited(it, out, Packet.Document.MAX_SIZE) }
        }.getOrNull()
        return when {
            written == null || written == 0L -> PickedFile.Unreadable.also { out.parentFile?.deleteRecursively() }
            written > Packet.Document.MAX_SIZE -> PickedFile.TooBig.also { out.parentFile?.deleteRecursively() }
            else -> PickedFile.Copied(out, safeName, mime)
        }
    }

    /** Copies a received file to where the user chose on the system "Save" screen. */
    fun saveFile(messageId: Long, destination: Uri) {
        val document = _messages.value.firstOrNull { it.id == messageId }?.document ?: return
        val file = document.file ?: return
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openOutputStream(destination)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    } != null
                }.getOrDefault(false)
            }
            _events.tryEmit(if (saved) NearbyEvent.FileSaved(document.name) else NearbyEvent.FileSaveFailed)
        }
    }

    private fun sendAttachment(
        peer: Peer,
        attachment: Attachment,
        announce: (payloadId: Long, size: Long, ts: Long) -> Packet,
    ) {
        val file = attachment.file ?: return
        val message = Message(
            nextMessageId++, text = "", System.currentTimeMillis(), fromMe = true, attachment = attachment,
        )
        _messages.update { it + message }

        if (FakePeers.isFake(peer.endpointId)) {
            fakeAttachmentReply(message)
            return
        }
        val size = file.length()
        val payload = Payload.fromStream(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        transferMessages[payload.id] = message.id
        transferSizes[payload.id] = size
        send(peer.endpointId, announce(payload.id, size, message.timestamp))
        client.sendPayload(peer.endpointId, payload).addOnFailureListener { e ->
            Log.w(TAG, "sending attachment failed", e)
            updateMessage(message.id) { it.copy(failed = true) }
        }
    }

    private fun onImageAnnounced(packet: Packet.Image) {
        if (packet.size !in 1..Packet.Image.MAX_SIZE || packet.w <= 0 || packet.h <= 0) return
        expectAttachment(packet.payloadId, packet.size, ImageAttachment(null, packet.w, packet.h, progress = 0f))
    }

    private fun onVoiceAnnounced(packet: Packet.Voice) {
        if (packet.size !in 1..Packet.Voice.MAX_SIZE || packet.durationMs <= 0) return
        val wave = packet.wave.map { it / 100f }
        expectAttachment(packet.payloadId, packet.size, VoiceAttachment(null, packet.durationMs, wave, progress = 0f))
    }

    private fun onFileAnnounced(packet: Packet.Document) {
        if (packet.size !in 1..Packet.Document.MAX_SIZE) return
        expectAttachment(
            packet.payloadId, packet.size,
            FileAttachment(null, packet.name, packet.mime, packet.size, progress = 0f),
        )
    }

    private fun expectAttachment(payloadId: Long, size: Long, attachment: Attachment) {
        // Whatever they were typing or recording has now arrived.
        setPeerActivity(ChatActivity.NONE)
        val message = Message(
            nextMessageId++, text = "", System.currentTimeMillis(), fromMe = false, attachment = attachment,
        )
        _messages.update { it + message }
        transferMessages[payloadId] = message.id
        transferSizes[payloadId] = size
        earlyStreams.remove(payloadId)?.let(::readAttachment)
    }

    private fun onAttachmentStream(endpointId: String, payload: Payload) {
        if (connectedPeer()?.endpointId != endpointId) {
            client.cancelPayload(payload.id)
            return
        }
        if (payload.id in transferMessages) readAttachment(payload) else earlyStreams[payload.id] = payload
    }

    /** Copies an incoming stream to a file; Nearby feeds it as bytes arrive. */
    private fun readAttachment(payload: Payload) {
        val messageId = transferMessages[payload.id] ?: return
        val expected = transferSizes[payload.id] ?: return
        val file = when (val attachment = _messages.value.firstOrNull { it.id == messageId }?.attachment) {
            is VoiceAttachment -> File(chatFilesDir, "in-${payload.id}.m4a")
            // Its own folder, so the file keeps its real name for opening and saving.
            is FileAttachment -> File(chatFilesDir, "in-${payload.id}/${attachment.name}")
            else -> File(chatFilesDir, "in-${payload.id}.jpg")
        }
        transferJobs += scope.launch {
            val complete = withContext(Dispatchers.IO) {
                runCatching {
                    file.parentFile?.mkdirs()
                    val input = payload.asStream()?.asInputStream() ?: return@runCatching false
                    input.use { copyLimited(it, file, limit = expected) } == expected
                }.getOrDefault(false)
            }
            if (complete) {
                updateAttachment(messageId) { it.withTransfer(file = file, progress = null) }
            } else {
                file.delete()
                updateMessage(messageId) { it.copy(failed = true) }
            }
        }
    }

    private fun onTransferProgress(update: PayloadTransferUpdate) {
        val messageId = transferMessages[update.payloadId] ?: return
        when (update.status) {
            PayloadTransferUpdate.Status.IN_PROGRESS -> {
                val total = transferSizes[update.payloadId]?.takeIf { it > 0 } ?: return
                val fraction = (update.bytesTransferred / total.toFloat()).coerceIn(0f, 1f)
                updateAttachment(messageId) { if (it.progress != null) it.withTransfer(progress = fraction) else it }
            }
            // For attachments we receive, readAttachment() marks them complete once the file is written.
            PayloadTransferUpdate.Status.SUCCESS ->
                if (_messages.value.any { it.id == messageId && it.fromMe }) {
                    updateAttachment(messageId) { it.withTransfer(progress = null) }
                }
            PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED ->
                updateMessage(messageId) { it.copy(failed = true) }
        }
    }

    /** Ends this chat's transfers and deletes its photos and voice messages. */
    private fun clearAttachments() {
        transferJobs.forEach { it.cancel() }
        transferJobs.clear()
        earlyStreams.values.forEach { runCatching { it.asStream()?.asInputStream()?.close() } }
        earlyStreams.clear()
        transferMessages.clear()
        transferSizes.clear()
        val dir = chatFilesDir
        scope.launch(Dispatchers.IO) { dir.deleteRecursively() }
    }

    private fun updateMessage(id: Long, change: (Message) -> Message) =
        _messages.update { list -> list.map { if (it.id == id) change(it) else it } }

    private fun updateAttachment(id: Long, change: (Attachment) -> Attachment) =
        updateMessage(id) { message -> message.attachment?.let { message.copy(attachment = change(it)) } ?: message }

    // endregion

    // region Typing and recording indicator

    /**
     * Tells the other phone what we're doing. Cheap to call on every keystroke: a
     * change is sent at once, the same state at most every [ACTIVITY_REPEAT_MS].
     */
    fun setMyActivity(activity: ChatActivity) {
        val peer = connectedPeer() ?: return
        val now = SystemClock.elapsedRealtime()
        if (activity == myActivity && (activity == ChatActivity.NONE || now - myActivitySentAt < ACTIVITY_REPEAT_MS)) return
        myActivity = activity
        myActivitySentAt = now
        if (!FakePeers.isFake(peer.endpointId)) send(peer.endpointId, Packet.Typing(activity.wireName))
    }

    private fun onPeerActivity(what: String) {
        // Ignore states a newer version might add.
        val activity = ChatActivity.entries.firstOrNull { it.wireName == what } ?: return
        setPeerActivity(activity)
    }

    /** Shows [activity] for the other person; it fades unless they keep repeating it. */
    private fun setPeerActivity(activity: ChatActivity) {
        peerActivityTimeout?.cancel()
        _peerActivity.value = activity
        if (activity != ChatActivity.NONE) {
            peerActivityTimeout = scope.launch {
                delay(ACTIVITY_TIMEOUT_MS)
                _peerActivity.value = ChatActivity.NONE
            }
        }
    }

    private fun resetActivity() {
        myActivity = ChatActivity.NONE
        setPeerActivity(ChatActivity.NONE)
    }

    private val ChatActivity.wireName: String get() = name.lowercase()

    private fun connectedPeer(): Peer? = (_status.value as? PeerStatus.Connected)?.peer

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
            delay(FAKE_REPLY_DELAY_MS / 3)
            val status = _status.value as? PeerStatus.Connected ?: return@launch
            when (text) {
                "/bye" -> endChat(status.peer, PeerStatus.Disconnected.Reason.LEFT)
                "/lost" -> endChat(status.peer, PeerStatus.Disconnected.Reason.OUT_OF_RANGE)
                else -> {
                    // Let the typing indicator show before the answer arrives.
                    setPeerActivity(ChatActivity.TYPING)
                    delay(FAKE_REPLY_DELAY_MS)
                    receiveMessage("You said: $text")
                }
            }
        }
    }

    /** Test Bot "receives" the photo or voice message, then sends the same one back. */
    private fun fakeAttachmentReply(sent: Message) {
        val attachment = sent.attachment ?: return
        transferJobs += scope.launch {
            for (step in 1..FAKE_TRANSFER_STEPS) {
                delay(FAKE_TRANSFER_STEP_MS)
                updateAttachment(sent.id) { it.withTransfer(progress = step / FAKE_TRANSFER_STEPS.toFloat()) }
            }
            updateAttachment(sent.id) { it.withTransfer(progress = null) }

            delay(FAKE_REPLY_DELAY_MS / 3)
            if (_status.value !is PeerStatus.Connected) return@launch
            if (attachment is VoiceAttachment) {
                setPeerActivity(ChatActivity.RECORDING)
                delay(FAKE_REPLY_DELAY_MS)
            }
            val reply = Message(
                nextMessageId++, text = "", System.currentTimeMillis(), fromMe = false,
                attachment = attachment.withTransfer(file = null, progress = 0f),
            )
            setPeerActivity(ChatActivity.NONE)
            _messages.update { it + reply }
            for (step in 1..FAKE_TRANSFER_STEPS) {
                delay(FAKE_TRANSFER_STEP_MS)
                updateAttachment(reply.id) { it.withTransfer(progress = step / FAKE_TRANSFER_STEPS.toFloat()) }
            }
            updateAttachment(reply.id) { it.withTransfer(file = attachment.file, progress = null) }
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
        private const val FAKE_TRANSFER_STEPS = 10
        private const val FAKE_TRANSFER_STEP_MS = 150L

        /** Resend "still typing" this often… */
        private const val ACTIVITY_REPEAT_MS = 3_000L

        /** …and drop the indicator if no repeat arrives within this. */
        private const val ACTIVITY_TIMEOUT_MS = 6_000L

        /** Give up if the other person hasn't answered by then. */
        private const val REQUEST_TIMEOUT_MS = 45_000L

        /** Time for the Busy packet to arrive before we disconnect. */
        private const val BUSY_HANGUP_DELAY_MS = 1_000L

        /** Time for the Bye packet to arrive before we disconnect. */
        private const val BYE_HANGUP_DELAY_MS = 500L
    }
}
