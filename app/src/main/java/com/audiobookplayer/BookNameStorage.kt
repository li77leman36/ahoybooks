package com.audiobookplayer

import android.content.Context
import android.content.SharedPreferences

class BookNameStorage(private val context: Context) {
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("audiobook_names", Context.MODE_PRIVATE)
    
    private fun getKey(bookPath: String): String {
        return "book_name_$bookPath"
    }
    
    fun saveBookName(bookPath: String, customName: String) {
        prefs.edit().putString(getKey(bookPath), customName).apply()
    }
    
    fun getBookName(bookPath: String): String? {
        return prefs.getString(getKey(bookPath), null)
    }
    
    fun clearBookName(bookPath: String) {
        prefs.edit().remove(getKey(bookPath)).apply()
    }
    
    fun getDisplayName(bookPath: String, defaultName: String): String {
        return getBookName(bookPath) ?: defaultName
    }
}

