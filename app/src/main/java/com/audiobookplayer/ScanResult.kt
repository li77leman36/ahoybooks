package com.audiobookplayer

data class ScanResult(
    val folderPath: String,
    val folderExists: Boolean,
    val folderReadable: Boolean,
    val totalItems: Int,
    val totalFiles: Int,
    val audioFiles: Int,
    val subfolders: Int,
    val sampleFiles: List<String>,
    val sampleAudioFiles: List<String>,
    val errorMessage: String?
)


