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

    @Test fun `garbage and unknown types decode to null`() {
        assertNull(Packet.decode("not json".encodeToByteArray()))
        assertNull(Packet.decode("""{"t":"image"}""".encodeToByteArray()))
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
