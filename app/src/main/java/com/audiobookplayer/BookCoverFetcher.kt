package com.audiobookplayer

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object BookCoverFetcher {
    
    private val cache = mutableMapOf<String, Bitmap?>()
    
    suspend fun getBookCover(context: Context, book: Book, forceRefresh: Boolean = false): Bitmap? {
        // Check cache first (unless forcing refresh)
        if (!forceRefresh) {
            cache[book.name]?.let { 
                Log.d("BookCoverFetcher", "Using cached cover for: ${book.name}")
                return it 
            }
        }
        
        return withContext(Dispatchers.IO) {
            try {
                Log.d("BookCoverFetcher", "Fetching cover for: ${book.name}")
                
                // First, try to get embedded album art
                if (book.audioFiles.isNotEmpty()) {
                    Log.d("BookCoverFetcher", "Checking for embedded art in: ${book.audioFiles[0].name}")
                    val embeddedArt = AlbumArtExtractor.extractAlbumArt(context, book.audioFiles[0])
                    if (embeddedArt != null) {
                        Log.d("BookCoverFetcher", "✓ Found embedded art for: ${book.name}")
                        cache[book.name] = embeddedArt
                        return@withContext embeddedArt
                    } else {
                        Log.d("BookCoverFetcher", "No embedded art found")
                    }
                }
                
                // Generate a cover with the book title
                Log.d("BookCoverFetcher", "Generating cover for: ${book.name}")
                val generatedCover = CoverGenerator.generateCover(book.name)
                cache[book.name] = generatedCover
                return@withContext generatedCover
            } catch (e: Exception) {
                Log.e("BookCoverFetcher", "Error fetching cover for ${book.name}: ${e.message}", e)
                e.printStackTrace()
                // Generate fallback cover on error
                try {
                    val generatedCover = CoverGenerator.generateCover(book.name)
                    cache[book.name] = generatedCover
                    return@withContext generatedCover
                } catch (genError: Exception) {
                    Log.e("BookCoverFetcher", "Error generating fallback cover: ${genError.message}")
                    null
                }
            }
        }
    }
    
    
    fun clearCache() {
        cache.clear()
    }
}

