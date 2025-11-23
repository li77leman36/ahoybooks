package com.audiobookplayer

import android.net.Uri
import java.io.File

data class AudioFile(
    val file: File?,
    val uri: Uri?
) {
    val name: String
        get() = file?.name ?: uri?.lastPathSegment ?: "Unknown"
    
    val exists: Boolean
        get() = file?.exists() ?: false
    
    val canRead: Boolean
        get() = file?.canRead() ?: false
    
    val length: Long
        get() = file?.length() ?: 0L
    
    val absolutePath: String
        get() = file?.absolutePath ?: uri?.toString() ?: ""
}

data class Book(
    val name: String,
    val folderPath: String,
    val audioFiles: List<AudioFile>
) {
    val totalDuration: Long
        get() = audioFiles.sumOf { it.length } // Approximate, actual duration would need MediaMetadataRetriever
}

