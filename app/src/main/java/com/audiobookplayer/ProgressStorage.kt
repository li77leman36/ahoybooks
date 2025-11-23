package com.audiobookplayer

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ProgressStorage(private val context: Context) {
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("audiobook_progress", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveProgress(bookPath: String, progress: BookProgress) {
        val json = gson.toJson(progress)
        prefs.edit().putString(bookPath, json).apply()
    }

    fun getProgress(bookPath: String): BookProgress? {
        val json = prefs.getString(bookPath, null) ?: return null
        return try {
            gson.fromJson(json, BookProgress::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun getAllProgress(): Map<String, BookProgress> {
        val allEntries = prefs.all
        val progressMap = mutableMapOf<String, BookProgress>()
        
        allEntries.forEach { (key, value) ->
            if (value is String) {
                try {
                    val progress = gson.fromJson(value, BookProgress::class.java)
                    progressMap[key] = progress
                } catch (e: Exception) {
                    // Skip invalid entries
                }
            }
        }
        
        return progressMap
    }

    fun clearProgress(bookPath: String) {
        prefs.edit().remove(bookPath).apply()
    }
}


