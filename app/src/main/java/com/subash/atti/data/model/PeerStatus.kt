package com.subash.atti.data.model

/** Where this phone is in talking to one other person. */
sealed interface PeerStatus {
    /** Not talking to anyone; free to send or receive a request. */
    data object Idle : PeerStatus

    /** We tapped [peer] and are waiting for them to accept. */
    data class Requesting(val peer: Peer) : PeerStatus

    /** We accepted [peer]'s request and the link is opening. */
    data class Accepting(val peer: Peer) : PeerStatus

    data class Connected(val peer: Peer) : PeerStatus

    /** The chat with [peer] ended from their side or went out of range; messages stay visible. */
    data class Disconnected(val peer: Peer, val reason: Reason) : PeerStatus {
        enum class Reason { LEFT, OUT_OF_RANGE }
    }
}

/** One-off things the UI should tell the user about. */
sealed interface NearbyEvent {
    data class Declined(val peer: Peer) : NearbyEvent
    data class Busy(val peer: Peer) : NearbyEvent
    data class Failed(val peer: Peer) : NearbyEvent
    data class NoAnswer(val peer: Peer) : NearbyEvent
    data class Error(val message: String) : NearbyEvent

    /** The picked photo couldn't be read or compressed. */
    data object PhotoUnreadable : NearbyEvent
}
