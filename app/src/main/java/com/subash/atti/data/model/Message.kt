package com.subash.atti.data.model

import java.io.File

data class Message(
    val id: Long,
    val text: String,
    val timestamp: Long,
    val fromMe: Boolean,
    val failed: Boolean = false,
    /** Set for photo messages; [text] is empty then. */
    val image: ImageAttachment? = null,
) {
    companion object {
        const val MAX_LENGTH = 1000
    }
}

data class ImageAttachment(
    /** The JPEG on this phone; null while a received photo is still arriving. */
    val file: File?,
    val width: Int,
    val height: Int,
    /** 0–1 while sending or receiving; null once the whole photo is here. */
    val progress: Float?,
) {
    val aspectRatio: Float get() = if (height > 0) width / height.toFloat() else 1f
}
