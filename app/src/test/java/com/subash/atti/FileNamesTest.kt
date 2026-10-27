package com.subash.atti

import com.subash.atti.data.FileNames
import com.subash.atti.data.Packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {
    @Test fun `folders are stripped from names`() {
        assertEquals("passwd", FileNames.safe("../../etc/passwd"))
        assertEquals("evil.exe", FileNames.safe("C:\\Windows\\evil.exe"))
    }

    @Test fun `hidden, reserved and control characters are removed`() {
        assertEquals("notes.txt", FileNames.safe("..notes.txt"))
        assertEquals("ab.pdf", FileNames.safe("a<\u0000>b.pdf"))
    }

    @Test fun `empty names get a fallback`() {
        assertEquals("file", FileNames.safe(""))
        assertEquals("file", FileNames.safe("///"))
    }

    @Test fun `long names are shortened but keep the extension`() {
        val name = FileNames.safe("a".repeat(300) + ".pdf")
        assertEquals(100, name.length)
        assertTrue(name.endsWith(".pdf"))
    }

    @Test fun `unicode names survive`() {
        assertEquals("வணக்கம் 👋.pdf", FileNames.safe("வணக்கம் 👋.pdf"))
    }

    @Test fun `bad mime types become generic`() {
        assertEquals("application/pdf", FileNames.safeMime("Application/PDF"))
        assertEquals("application/octet-stream", FileNames.safeMime("text/html; script"))
        assertEquals("application/octet-stream", FileNames.safeMime(""))
    }

    @Test fun `extensions and sizes`() {
        assertEquals("pdf", FileNames.extension("report.final.PDF"))
        assertEquals("", FileNames.extension("README"))
        assertEquals("512 B", FileNames.formatSize(512))
        assertEquals("1.5 KB", FileNames.formatSize(1536))
        assertEquals("3.1 MB", FileNames.formatSize(3_250_000))
    }

    @Test fun `file packets are cleaned on arrival`() {
        val raw = """{"t":"file","payloadId":1,"size":2,"name":"../x/report.pdf","mime":"BAD MIME","ts":3}"""
        val packet = Packet.decode(raw.encodeToByteArray()) as Packet.Document
        assertEquals("report.pdf", packet.name)
        assertEquals("application/octet-stream", packet.mime)
    }

    @Test fun `file packet round trip`() {
        val packet = Packet.Document(payloadId = 9, size = 1234, name = "notes.txt", mime = "text/plain", ts = 1)
        assertEquals(packet, Packet.decode(Packet.encode(packet)))
    }
}
