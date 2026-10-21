package com.subash.atti.data.model

data class Message(
    val id: Long,
    val text: String,
    val timestamp: Long,
    val fromMe: Boolean,
    val failed: Boolean = false,
) {
    companion object {
        const val MAX_LENGTH = 1000
    }
}
