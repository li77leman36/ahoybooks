package com.audiobookplayer

import android.content.Context
import android.content.SharedPreferences

class FolderPreferences(private val context: Context) {
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("audiobook_prefs", Context.MODE_PRIVATE)
    
    private val KEY_SELECTED_FOLDER = "selected_folder_uri"
    private val KEY_SELECTED_FOLDER_PATH = "selected_folder_path"
    
    fun saveSelectedFolderUri(uri: String?) {
        prefs.edit().putString(KEY_SELECTED_FOLDER, uri).apply()
    }
    
    fun getSelectedFolderUri(): String? {
        return prefs.getString(KEY_SELECTED_FOLDER, null)
    }
    
    fun saveSelectedFolderPath(path: String?) {
        android.util.Log.d("FolderPreferences", "Saving folder path: $path")
        val result = prefs.edit().putString(KEY_SELECTED_FOLDER_PATH, path).commit()
        android.util.Log.d("FolderPreferences", "Save result: $result")
        // Also verify it was saved
        val verify = prefs.getString(KEY_SELECTED_FOLDER_PATH, null)
        android.util.Log.d("FolderPreferences", "Verification read: $verify")
    }
    
    fun getSelectedFolderPath(): String? {
        val path = prefs.getString(KEY_SELECTED_FOLDER_PATH, null)
        android.util.Log.d("FolderPreferences", "Reading folder path: $path")
        return path
    }
    
    fun clearSelectedFolder() {
        prefs.edit()
            .remove(KEY_SELECTED_FOLDER)
            .remove(KEY_SELECTED_FOLDER_PATH)
            .apply()
    }
}

