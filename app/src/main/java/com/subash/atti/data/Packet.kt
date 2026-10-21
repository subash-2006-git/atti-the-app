package com.subash.atti.data

import com.subash.atti.data.model.Message
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What two ATTI phones send each other over a Nearby link, as small JSON
 * byte payloads, e.g. {"t":"msg","text":"hello","ts":1729500000000}.
 */
@Serializable
sealed interface Packet {
    /** Sent by the person who tapped Accept: the chat is open. */
    @Serializable @SerialName("hi") data object Hi : Packet

    /** Sent instead of [Hi] when this phone is already in a chat. */
    @Serializable @SerialName("busy") data object Busy : Packet

    /** Sent when someone leaves the chat, so the other side knows right away. */
    @Serializable @SerialName("bye") data object Bye : Packet

    @Serializable @SerialName("msg") data class Text(val text: String, val ts: Long) : Packet

    companion object {
        private val json = Json {
            classDiscriminator = "t"
            ignoreUnknownKeys = true
        }

        fun encode(packet: Packet): ByteArray =
            json.encodeToString(serializer(), packet).encodeToByteArray()

        /** Returns null for anything that isn't a packet we understand. */
        fun decode(bytes: ByteArray): Packet? {
            val packet = runCatching { json.decodeFromString(serializer(), bytes.decodeToString()) }
                .getOrNull()
            return if (packet is Text) packet.copy(text = packet.text.take(Message.MAX_LENGTH)) else packet
        }
    }
}
