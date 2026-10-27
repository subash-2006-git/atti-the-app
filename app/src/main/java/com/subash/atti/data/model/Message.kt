package com.subash.atti.data.model

import java.io.File

data class Message(
    val id: Long,
    val text: String,
    val timestamp: Long,
    val fromMe: Boolean,
    val failed: Boolean = false,
    /** Set for photo, voice and file messages; [text] is empty then. */
    val attachment: Attachment? = null,
) {
    val image: ImageAttachment? get() = attachment as? ImageAttachment
    val voice: VoiceAttachment? get() = attachment as? VoiceAttachment
    val document: FileAttachment? get() = attachment as? FileAttachment

    companion object {
        const val MAX_LENGTH = 1000
    }
}

/** A file sent alongside a message: it travels separately and can be in progress. */
sealed interface Attachment {
    /** The file on this phone; null while a received attachment is still arriving. */
    val file: File?

    /** 0–1 while sending or receiving; null once the whole file is here. */
    val progress: Float?

    fun withTransfer(file: File? = this.file, progress: Float?): Attachment
}

data class ImageAttachment(
    override val file: File?,
    val width: Int,
    val height: Int,
    override val progress: Float?,
) : Attachment {
    val aspectRatio: Float get() = if (height > 0) width / height.toFloat() else 1f

    override fun withTransfer(file: File?, progress: Float?) = copy(file = file, progress = progress)
}

data class VoiceAttachment(
    override val file: File?,
    val durationMs: Long,
    /** Loudness bars, each 0–1, drawn as the waveform. */
    val waveform: List<Float>,
    override val progress: Float?,
) : Attachment {
    override fun withTransfer(file: File?, progress: Float?) = copy(file = file, progress = progress)
}

/** Any other file (PDF, document, APK, …), shown with its name and size. */
data class FileAttachment(
    override val file: File?,
    val name: String,
    val mimeType: String,
    val size: Long,
    override val progress: Float?,
) : Attachment {
    override fun withTransfer(file: File?, progress: Float?) = copy(file = file, progress = progress)
}

/** What the other person is doing right now, shown under their name. */
enum class ChatActivity { NONE, TYPING, RECORDING }
