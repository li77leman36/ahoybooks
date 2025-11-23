package com.audiobookplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File

object AlbumArtExtractor {
    
    fun extractAlbumArt(context: Context, audioFile: AudioFile): Bitmap? {
        return try {
            if (audioFile.file != null && audioFile.file.exists()) {
                extractFromFile(audioFile.file)
            } else if (audioFile.uri != null) {
                extractFromUri(context, audioFile.uri)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("AlbumArtExtractor", "Error extracting album art: ${e.message}", e)
            null
        }
    }
    
    private fun extractFromFile(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val picture = retriever.embeddedPicture
            if (picture != null) {
                BitmapFactory.decodeByteArray(picture, 0, picture.size)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("AlbumArtExtractor", "Error extracting from file: ${e.message}")
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
    
    private fun extractFromUri(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val picture = retriever.embeddedPicture
            if (picture != null) {
                BitmapFactory.decodeByteArray(picture, 0, picture.size)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("AlbumArtExtractor", "Error extracting from URI: ${e.message}")
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}


