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

    /**
     * Announces a photo. Its JPEG bytes follow as a separate Nearby stream payload
     * with id [payloadId] (byte packets are capped at 32 KB, photos are bigger).
     */
    @Serializable @SerialName("img")
    data class Image(val payloadId: Long, val size: Long, val w: Int, val h: Int, val ts: Long) : Packet {
        companion object {
            /** Refuse anything bigger; our own photos are a few hundred KB. */
            const val MAX_SIZE = 10L * 1024 * 1024
        }
    }

    /**
     * Announces a voice message. Its AAC audio follows as a stream payload with id
     * [payloadId], like a photo. [wave] is the loudness shape, each bar 0–100.
     */
    @Serializable @SerialName("voice")
    data class Voice(
        val payloadId: Long,
        val size: Long,
        val durationMs: Long,
        val wave: List<Int>,
        val ts: Long,
    ) : Packet {
        companion object {
            const val MAX_SIZE = 5L * 1024 * 1024
            const val MAX_BARS = 64
        }
    }

    /**
     * Announces any other file. Its bytes follow as a stream payload with id
     * [payloadId], like a photo. [name] is only a display name, never a path.
     */
    @Serializable @SerialName("file")
    data class Document(
        val payloadId: Long,
        val size: Long,
        val name: String,
        val mime: String,
        val ts: Long,
    ) : Packet {
        companion object {
            const val MAX_SIZE = 100L * 1024 * 1024
        }
    }

    /**
     * What the sender is doing: "typing", "recording" or "none". Repeated every few
     * seconds while it lasts; the receiver forgets it if the repeats stop.
     */
    @Serializable @SerialName("typing") data class Typing(val what: String) : Packet

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
            return when (packet) {
                is Text -> packet.copy(text = packet.text.take(Message.MAX_LENGTH))
                is Voice -> packet.copy(wave = packet.wave.take(Voice.MAX_BARS).map { it.coerceIn(0, 100) })
                is Document -> packet.copy(name = FileNames.safe(packet.name), mime = FileNames.safeMime(packet.mime))
                else -> packet
            }
        }
    }
}
