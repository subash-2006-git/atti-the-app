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
import com.google.android.gms.nearby.connection.Strategy
import com.subash.atti.BuildConfig
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

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _status = MutableStateFlow<PeerStatus>(PeerStatus.Idle)
    val status: StateFlow<PeerStatus> = _status.asStateFlow()

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
        _peers.value = emptyList()
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

    // region Connections

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Connection requests arrive in a later step; refuse them for now.
            client.rejectConnection(endpointId)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) = Unit

        override fun onDisconnected(endpointId: String) = Unit
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
    }
}
