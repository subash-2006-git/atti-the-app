package com.subash.atti.data

import com.subash.atti.data.model.Peer

/**
 * Pretend nearby users, shown only in debug builds so every screen can be
 * tried with a single phone. Each bot answers a chat request differently.
 *
 * In a chat with Test Bot: it echoes messages, "/bye" makes it leave and
 * "/lost" makes it go out of range.
 */
object FakePeers {
    val TestBot = Peer("fake-accept", "Test Bot")
    val ShyBot = Peer("fake-decline", "Shy Bot")
    val BusyBot = Peer("fake-busy", "Busy Bot")

    val all = listOf(TestBot, ShyBot, BusyBot)

    fun isFake(endpointId: String) = endpointId.startsWith("fake-")
}
