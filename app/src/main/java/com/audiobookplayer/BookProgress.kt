package com.audiobookplayer

data class BookProgress(
    val bookPath: String,
    val currentFileIndex: Int,
    val currentPosition: Int, // Position in milliseconds
    val queueOrder: List<Int>? = null, // Saved queue order (list of file indices) - nullable for backward compatibility
    val queueCurrentIndex: Int? = null // Current position in queue - nullable for backward compatibility
) {
    companion object {
        const val REWIND_MS = 10000 // 10 seconds
        const val FORWARD_MS = 10000 // 10 seconds
    }
}


