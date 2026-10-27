package com.subash.atti.data

import java.util.Locale

/** Keeps file names and types from another phone harmless to show and save. */
object FileNames {
    private const val MAX_NAME = 100
    private const val FALLBACK = "file"
    private val MIME = Regex("^[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*$")

    /**
     * A display name with no folders, control or reserved characters, at most
     * [MAX_NAME] characters, keeping the extension when shortening.
     */
    fun safe(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base
            .filterNot { it.isISOControl() || it in "<>:\"|?*" }
            .trim()
            .trimStart('.')
        if (cleaned.isEmpty()) return FALLBACK
        if (cleaned.length <= MAX_NAME) return cleaned
        val ext = extension(cleaned)
        val keep = MAX_NAME - (if (ext.isEmpty()) 0 else ext.length + 1)
        return cleaned.take(keep).trimEnd() + if (ext.isEmpty()) "" else ".$ext"
    }

    /** A well-formed lower-case MIME type, or the generic binary type. */
    fun safeMime(raw: String): String {
        val mime = raw.trim().lowercase(Locale.ROOT)
        return if (MIME.matches(mime)) mime else "application/octet-stream"
    }

    /** "report.final.PDF" → "pdf"; no dot → "". Long or odd endings count as none. */
    fun extension(name: String): String {
        val ext = name.substringAfterLast('.', "")
        return if (ext.length in 1..8 && ext.all { it.isLetterOrDigit() }) ext.lowercase(Locale.ROOT) else ""
    }

    /** 1536 → "1.5 KB", 3_250_000 → "3.1 MB". */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }
}
