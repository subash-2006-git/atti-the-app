package com.subash.atti.data

import java.io.File
import java.io.InputStream

/**
 * Copies [input] into [file] but stops one byte past [limit], so a sender that
 * lied about the size can't fill the phone. Returns the number of bytes written.
 */
internal fun copyLimited(input: InputStream, file: File, limit: Long): Long {
    var written = 0L
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    file.outputStream().use { out ->
        while (written <= limit) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limit + 1 - written).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            written += read
        }
    }
    return written
}
