package com.subash.atti

import com.subash.atti.data.copyLimited
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class StreamsTest {
    private val file = File.createTempFile("atti", ".bin").apply { deleteOnExit() }

    @Test fun `copies a stream of exactly the expected size`() {
        val bytes = ByteArray(20_000) { it.toByte() }
        assertEquals(20_000L, copyLimited(bytes.inputStream(), file, limit = 20_000))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun `a short stream reports how much arrived`() {
        assertEquals(10L, copyLimited(ByteArray(10).inputStream(), file, limit = 100))
    }

    @Test fun `stops one byte past the limit when the sender lies`() {
        assertEquals(101L, copyLimited(ByteArray(1_000_000).inputStream(), file, limit = 100))
        assertEquals(101L, file.length())
    }
}
