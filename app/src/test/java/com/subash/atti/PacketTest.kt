package com.subash.atti

import com.subash.atti.data.Packet
import com.subash.atti.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketTest {
    @Test fun `every packet survives a round trip`() {
        listOf(Packet.Hi, Packet.Busy, Packet.Bye, Packet.Text("vanakkam 👋", 1_729_500_000_000)).forEach {
            assertEquals(it, Packet.decode(Packet.encode(it)))
        }
    }

    @Test fun `text uses the documented wire format`() {
        assertEquals(
            """{"t":"msg","text":"hi","ts":5}""",
            Packet.encode(Packet.Text("hi", 5)).decodeToString(),
        )
        assertEquals("""{"t":"busy"}""", Packet.encode(Packet.Busy).decodeToString())
    }

    @Test fun `image announcement survives a round trip`() {
        val image = Packet.Image(payloadId = -4_611_686_018_427_387_904, size = 312_000, w = 1600, h = 1200, ts = 9)
        assertEquals(image, Packet.decode(Packet.encode(image)))
        assertEquals(
            """{"t":"img","payloadId":7,"size":100,"w":3,"h":4,"ts":5}""",
            Packet.encode(Packet.Image(7, 100, 3, 4, 5)).decodeToString(),
        )
    }

    @Test fun `voice announcement survives a round trip`() {
        val voice = Packet.Voice(payloadId = 42, size = 48_000, durationMs = 12_345, wave = listOf(0, 50, 100), ts = 7)
        assertEquals(voice, Packet.decode(Packet.encode(voice)))
    }

    @Test fun `voice waveform is clamped and capped`() {
        val raw = """{"t":"voice","payloadId":1,"size":2,"durationMs":3,"wave":[-5,250${",1".repeat(100)}],"ts":4}"""
        val decoded = Packet.decode(raw.encodeToByteArray()) as Packet.Voice
        assertEquals(Packet.Voice.MAX_BARS, decoded.wave.size)
        assertEquals(listOf(0, 100), decoded.wave.take(2))
    }

    @Test fun `typing uses the documented wire format`() {
        assertEquals("""{"t":"typing","what":"typing"}""", Packet.encode(Packet.Typing("typing")).decodeToString())
        assertEquals(Packet.Typing("recording"), Packet.decode("""{"t":"typing","what":"recording"}""".encodeToByteArray()))
    }

    @Test fun `garbage and unknown types decode to null`() {
        assertNull(Packet.decode("not json".encodeToByteArray()))
        assertNull(Packet.decode("""{"t":"video"}""".encodeToByteArray()))
    }

    @Test fun `unknown fields from a newer version are ignored`() {
        assertEquals(Packet.Text("hi", 5), Packet.decode("""{"t":"msg","text":"hi","ts":5,"reply":1}""".encodeToByteArray()))
    }

    @Test fun `over-long text is cut to the limit`() {
        val long = "a".repeat(Message.MAX_LENGTH + 50)
        val decoded = Packet.decode(Packet.encode(Packet.Text(long, 1))) as Packet.Text
        assertEquals(Message.MAX_LENGTH, decoded.text.length)
    }
}
