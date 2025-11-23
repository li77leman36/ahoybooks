package com.audiobookplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.view.ContextThemeWrapper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.os.Environment

class MainActivity : AppCompatActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: BookAdapter
    private lateinit var bookScanner: BookScanner
    private lateinit var progressStorage: ProgressStorage
    private lateinit var folderPreferences: FolderPreferences
    private lateinit var bookNameStorage: BookNameStorage
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar
    private var books = mutableListOf<Book>()
    
    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 101
        private const val FOLDER_SELECT_REQUEST_CODE = 200
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        bookScanner = BookScanner(this)
        progressStorage = ProgressStorage(this)
        folderPreferences = FolderPreferences(this)
        bookNameStorage = BookNameStorage(this)
        
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        
        recyclerView = findViewById(R.id.recyclerView)
        
        adapter = BookAdapter { book ->
            openPlayer(book)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        
        // Restore URI permissions if we have a saved folder URI
        restoreUriPermissions()
        requestNotificationPermission()
        checkPermissionsAndScan()
    }
    
    override fun onResume() {
        super.onResume()
        // Restore URI permissions in case they were lost
        restoreUriPermissions()
    }
    
    private fun restoreUriPermissions() {
        val savedUri = folderPreferences.getSelectedFolderUri()
        if (savedUri != null) {
            try {
                val uri = Uri.parse(savedUri)
                // Restore persistent URI permission
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                android.util.Log.d("MainActivity", "Restored URI permission for: $savedUri")
            } catch (e: SecurityException) {
                android.util.Log.w("MainActivity", "Could not restore URI permission: ${e.message}")
                // If we can't restore permission, try to get path again
                try {
                    val uri = Uri.parse(savedUri)
                    val folderPath = getPathFromUri(uri)
                    if (folderPath != null && File(folderPath).exists()) {
                        folderPreferences.saveSelectedFolderPath(folderPath)
                    }
                } catch (e2: Exception) {
                    android.util.Log.e("MainActivity", "Error getting path from URI: ${e2.message}")
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error restoring URI permission: ${e.message}")
            }
        }
    }
    
    private fun checkPermissionsAndScan() {
        if (hasStoragePermission()) {
            scanForBooks()
        } else {
            requestStoragePermission()
        }
    }
    
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun requestStoragePermission() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        ActivityCompat.requestPermissions(
            this,
            permissions,
            PERMISSION_REQUEST_CODE
        )
    }
    
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    scanForBooks()
                } else {
                    showPermissionDeniedDialog()
                }
            }
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    android.util.Log.d("MainActivity", "Notification permission granted")
                } else {
                    android.util.Log.w("MainActivity", "Notification permission denied")
                }
            }
        }
    }
    
    private fun showPermissionDeniedDialog() {
        val lightContext = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog)
        MaterialAlertDialogBuilder(lightContext)
            .setTitle("Permission Required")
            .setMessage(getString(R.string.permission_required))
            .setPositiveButton("OK") { _, _ -> }
            .show()
    }
    
    private fun scanForBooks() {
        lifecycleScope.launch {
            // Show scanning message
            val emptyText = findViewById<TextView>(R.id.emptyText)
            val emptyScrollView = findViewById<android.widget.ScrollView>(R.id.emptyScrollView)
            emptyText.text = getString(R.string.scanning)
            emptyScrollView.visibility = View.VISIBLE
            
            val foundBooks = withContext(Dispatchers.IO) {
                try {
                    // Read preferences fresh
                    val selectedPath = folderPreferences.getSelectedFolderPath()
                    val selectedUri = folderPreferences.getSelectedFolderUri()
                    
                    android.util.Log.d("MainActivity", "═══════════════════════════════════════")
                    android.util.Log.d("MainActivity", "SCANNING FOR BOOKS")
                    android.util.Log.d("MainActivity", "═══════════════════════════════════════")
                    android.util.Log.d("MainActivity", "Reading from preferences:")
                    android.util.Log.d("MainActivity", "  Selected Path: $selectedPath")
                    android.util.Log.d("MainActivity", "  Selected URI: $selectedUri")
                    
                    // Verify the saved path
                    if (selectedPath != null) {
                        val testFile = File(selectedPath)
                        android.util.Log.d("MainActivity", "Verifying saved path:")
                        android.util.Log.d("MainActivity", "  Path: ${testFile.absolutePath}")
                        android.util.Log.d("MainActivity", "  Exists: ${testFile.exists()}")
                        android.util.Log.d("MainActivity", "  IsDirectory: ${testFile.isDirectory}")
                        android.util.Log.d("MainActivity", "  CanRead: ${testFile.canRead()}")
                    }
                    
                    // Determine which folder to scan - prioritize saved path
                    val pathToScan: String? = if (selectedPath != null) {
                        val folder = File(selectedPath)
                        if (folder.exists() && folder.isDirectory && folder.canRead()) {
                            android.util.Log.d("MainActivity", "✓ Using saved path: $selectedPath")
                            selectedPath
                        } else {
                            android.util.Log.w("MainActivity", "⚠ Saved path invalid: $selectedPath (exists: ${folder.exists()}, readable: ${folder.canRead()})")
                            // Try to recover from URI
                            if (selectedUri != null) {
                                try {
                                    android.util.Log.d("MainActivity", "Attempting to recover path from URI...")
                                    val uri = Uri.parse(selectedUri)
                                    val newPath = getPathFromUri(uri)
                                    if (newPath != null && File(newPath).exists() && File(newPath).canRead()) {
                                        android.util.Log.d("MainActivity", "✓ Recovered path from URI: $newPath")
                                        folderPreferences.saveSelectedFolderPath(newPath)
                                        newPath
                                    } else {
                                        android.util.Log.w("MainActivity", "⚠ Could not recover path from URI")
                                        null
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("MainActivity", "Error recovering path: ${e.message}")
                                    null
                                }
                            } else {
                                null
                            }
                        }
                    } else {
                        null
                    }
                    
                    // Scan based on what we have
                    when {
                        pathToScan != null -> {
                            android.util.Log.d("MainActivity", "═══════════════════════════════════════")
                            android.util.Log.d("MainActivity", "SCANNING SELECTED FOLDER: $pathToScan")
                            android.util.Log.d("MainActivity", "═══════════════════════════════════════")
                            bookScanner.scanForBooks(pathToScan)
                        }
                        selectedUri != null -> {
                            // No path but we have URI - use DocumentFile scanning
                            android.util.Log.d("MainActivity", "No saved path, but have URI - using DocumentFile scanning...")
                            try {
                                val uri = Uri.parse(selectedUri)
                                android.util.Log.d("MainActivity", "═══════════════════════════════════════")
                                android.util.Log.d("MainActivity", "SCANNING WITH URI: $uri")
                                android.util.Log.d("MainActivity", "═══════════════════════════════════════")
                                bookScanner.scanForBooksWithUri(this@MainActivity, uri)
                            } catch (e: Exception) {
                                android.util.Log.e("MainActivity", "Error scanning with URI: ${e.message}", e)
                                emptyList<Book>()
                            }
                        }
                        else -> {
                            // Use default Music directory
                            val defaultPath = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC).absolutePath
                            android.util.Log.d("MainActivity", "═══════════════════════════════════════")
                            android.util.Log.d("MainActivity", "SCANNING DEFAULT: $defaultPath")
                            android.util.Log.d("MainActivity", "═══════════════════════════════════════")
                            bookScanner.scanForBooks()
                        }
                    }
                } catch (e: SecurityException) {
                    android.util.Log.e("MainActivity", "SecurityException while scanning: ${e.message}")
                    emptyList<Book>()
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Exception while scanning: ${e.message}")
                    emptyList<Book>()
                }
            }
            
            books.clear()
            books.addAll(foundBooks)
            adapter.notifyDataSetChanged()
            
            if (books.isEmpty()) {
                val selectedPath = folderPreferences.getSelectedFolderPath()
                val defaultPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath
                val folderToScan = selectedPath ?: defaultPath
                
                android.util.Log.d("MainActivity", "Getting scan result for: $folderToScan")
                android.util.Log.d("MainActivity", "Selected path: $selectedPath")
                
                withContext(Dispatchers.IO) {
                    bookScanner.getScanResult(folderToScan)
                }
                
                emptyText.text = getString(R.string.no_books_found)
                emptyScrollView.visibility = View.VISIBLE
            } else {
                emptyScrollView.visibility = View.GONE
            }
        }
    }
    
    private fun browseForFolder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, FOLDER_SELECT_REQUEST_CODE)
        } else {
            // For older Android versions, show a message
            val lightContext = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog)
        MaterialAlertDialogBuilder(lightContext)
                .setTitle("Not Supported")
                .setMessage("Folder browsing requires Android 5.0 or higher. Please place audiobooks in the Music folder.")
                .setPositiveButton("OK") { _, _ -> }
                .show()
        }
    }
    
    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_browse_folder -> {
                browseForFolder()
                true
            }
            R.id.menu_refresh -> {
                refreshBooks()
                true
            }
            R.id.menu_reset_folder -> {
                resetToDefaultFolder()
                true
            }
            R.id.menu_download_librivox -> {
                openLibriVoxBrowser()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun refreshBooks() {
        // Clear cover cache to force reload of artwork
        BookCoverFetcher.clearCache()
        // Tell adapter to force refresh covers
        adapter.setForceRefreshCovers(true)
        scanForBooks()
        // Reset flag after a delay
        lifecycleScope.launch {
            kotlinx.coroutines.delay(100)
            adapter.setForceRefreshCovers(false)
        }
    }
    
    private fun resetToDefaultFolder() {
        folderPreferences.clearSelectedFolder()
        scanForBooks()
    }
    
    private fun openLibriVoxBrowser() {
        val intent = Intent(this, LibriVoxWebActivity::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 300) // Use request code 300 for LibriVox
    }
    
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == 300 && resultCode == RESULT_OK) {
            // LibriVox download completed, refresh book list
            scanForBooks()
        }
        
        if (requestCode == FOLDER_SELECT_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                data?.data?.let { treeUri ->
                    try {
                        // Take persistent permission
                        contentResolver.takePersistableUriPermission(
                            treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                        
                        // Save the URI
                        folderPreferences.saveSelectedFolderUri(treeUri.toString())
                        
                        android.util.Log.d("MainActivity", "═══════════════════════════════════════")
                        android.util.Log.d("MainActivity", "FOLDER SELECTED")
                        android.util.Log.d("MainActivity", "URI: $treeUri")
                        android.util.Log.d("MainActivity", "URI Authority: ${treeUri.authority}")
                        android.util.Log.d("MainActivity", "URI Path: ${treeUri.path}")
                        android.util.Log.d("MainActivity", "URI Last Segment: ${treeUri.lastPathSegment}")
                        
                        // Try to get the actual path from tree URI
                        val folderPath = try {
                            var path: String? = null
                            
                            // First, try to get path from tree URI
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                                if (DocumentsContract.isTreeUri(treeUri)) {
                                    val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
                                    android.util.Log.d("MainActivity", "Tree document ID: $treeDocId")
                                    
                                    if (treeDocId.startsWith("primary:")) {
                                        val pathPart = treeDocId.substring("primary:".length)
                                        val storageDir = android.os.Environment.getExternalStorageDirectory()
                                        path = java.io.File(storageDir, pathPart).absolutePath
                                        android.util.Log.d("MainActivity", "Extracted path from tree URI: $path")
                                    } else if (treeDocId.contains(":")) {
                                        // Handle other storage (e.g., SD card)
                                        val split = treeDocId.split(":", limit = 2)
                                        if (split.size == 2) {
                                            val storageId = split[0]
                                            val pathPart = split[1]
                                            val storageDir = java.io.File("/storage/$storageId")
                                            if (storageDir.exists()) {
                                                path = java.io.File(storageDir, pathPart).absolutePath
                                                android.util.Log.d("MainActivity", "Extracted path from tree URI (external): $path")
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // If tree URI method didn't work, try regular getPathFromUri
                            if (path == null) {
                                path = getPathFromUri(treeUri)
                                android.util.Log.d("MainActivity", "Path from regular URI conversion: $path")
                            }
                            
                            path
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Error getting path from URI: ${e.message}", e)
                            null
                        }
                        
                        if (folderPath != null) {
                            val folder = File(folderPath)
                            android.util.Log.d("MainActivity", "Checking folder: $folderPath")
                            android.util.Log.d("MainActivity", "  Exists: ${folder.exists()}")
                            android.util.Log.d("MainActivity", "  IsDirectory: ${folder.isDirectory}")
                            android.util.Log.d("MainActivity", "  CanRead: ${folder.canRead()}")
                            
                            if (folder.exists() && folder.isDirectory && folder.canRead()) {
                                android.util.Log.d("MainActivity", "✓ Saving folder path: $folderPath")
                                // Save both path and URI
                                folderPreferences.saveSelectedFolderPath(folderPath)
                                folderPreferences.saveSelectedFolderUri(treeUri.toString())
                                
                                // Verify it was saved
                                val savedPath = folderPreferences.getSelectedFolderPath()
                                android.util.Log.d("MainActivity", "Saved path verification: $savedPath")
                                
                                // Force immediate scan with the new folder
                                scanForBooks()
                            } else {
                                android.util.Log.w("MainActivity", "⚠ Folder path invalid or not accessible")
                                // Still save URI for future attempts
                                folderPreferences.saveSelectedFolderUri(treeUri.toString())
                                folderPreferences.saveSelectedFolderPath(null)
                                val lightContext = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog)
        MaterialAlertDialogBuilder(lightContext)
                                    .setTitle("Folder Access Issue")
                                    .setMessage("Selected folder path: $folderPath\n\nFolder exists: ${folder.exists()}\nCan read: ${folder.canRead()}\n\nPlease try selecting the folder again or check permissions.")
                                    .setPositiveButton("OK") { _, _ -> }
                                    .show()
                            }
                        } else {
                            // If we can't get the path, save just the URI and scan with DocumentFile
                            android.util.Log.w("MainActivity", "⚠ Could not convert URI to path - will use DocumentFile scanning")
                            android.util.Log.d("MainActivity", "Saving URI for future use: $treeUri")
                            folderPreferences.saveSelectedFolderUri(treeUri.toString())
                            folderPreferences.saveSelectedFolderPath(null)
                            
                            // Scan using DocumentFile API
                            android.util.Log.d("MainActivity", "Scanning folder using DocumentFile API...")
                            scanForBooks()
                            
                            // Show helpful message
                            val lightContext = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog)
        MaterialAlertDialogBuilder(lightContext)
                                .setTitle("Folder Selected")
                                .setMessage("Folder selected: ${treeUri.lastPathSegment ?: "Unknown"}\n\nUsing URI-based access. Scanning folder now...")
                                .setPositiveButton("OK") { _, _ -> }
                                .show()
                        }
                    } catch (e: SecurityException) {
                        val lightContext = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog)
        MaterialAlertDialogBuilder(lightContext)
                            .setTitle("Permission Error")
                            .setMessage("Could not get permission to access the selected folder. Please try selecting a different folder.")
                            .setPositiveButton("OK") { _, _ -> }
                            .show()
                    } catch (e: Exception) {
                        val lightContext = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog)
        MaterialAlertDialogBuilder(lightContext)
                            .setTitle("Error")
                            .setMessage("An error occurred while selecting the folder: ${e.message}")
                            .setPositiveButton("OK") { _, _ -> }
                            .show()
                    }
                } ?: run {
                    val lightContext = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog)
        MaterialAlertDialogBuilder(lightContext)
                        .setTitle("No Folder Selected")
                        .setMessage("No folder was selected.")
                        .setPositiveButton("OK") { _, _ -> }
                        .show()
                }
            }
            // If resultCode is not OK, user cancelled - do nothing
        }
    }
    
    private fun getPathFromUri(uri: Uri): String? {
        try {
            android.util.Log.d("MainActivity", "getPathFromUri - URI: $uri")
            android.util.Log.d("MainActivity", "  Scheme: ${uri.scheme}")
            android.util.Log.d("MainActivity", "  Authority: ${uri.authority}")
            android.util.Log.d("MainActivity", "  Path: ${uri.path}")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                if (DocumentsContract.isDocumentUri(this, uri)) {
                    android.util.Log.d("MainActivity", "  Is Document URI")
                    
                    if ("com.android.externalstorage.documents" == uri.authority) {
                        val docId = DocumentsContract.getDocumentId(uri)
                        android.util.Log.d("MainActivity", "  Document ID: $docId")
                        val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                        android.util.Log.d("MainActivity", "  Split parts: ${split.contentToString()}")
                        
                        if (split.size >= 2) {
                            val type = split[0]
                            
                            if ("primary".equals(type, ignoreCase = true)) {
                                val path = android.os.Environment.getExternalStorageDirectory().toString() + "/" + split[1]
                                android.util.Log.d("MainActivity", "  Primary storage path: $path")
                                if (File(path).exists()) {
                                    android.util.Log.d("MainActivity", "  ✓ Path exists: $path")
                                    return path
                                } else {
                                    android.util.Log.w("MainActivity", "  ✗ Path does not exist: $path")
                                }
                            } else {
                                // Handle other storage types (SD cards, etc.)
                                val storagePath = "/storage/$type/${split[1]}"
                                android.util.Log.d("MainActivity", "  Other storage path: $storagePath")
                                if (File(storagePath).exists()) {
                                    android.util.Log.d("MainActivity", "  ✓ Path exists: $storagePath")
                                    return storagePath
                                } else {
                                    android.util.Log.w("MainActivity", "  ✗ Path does not exist: $storagePath")
                                }
                            }
                        }
                    } else if ("com.android.providers.downloads.documents" == uri.authority) {
                        val id = DocumentsContract.getDocumentId(uri)
                        try {
                            val contentUri = android.content.ContentUris.withAppendedId(
                                Uri.parse("content://downloads/public_downloads"),
                                java.lang.Long.parseLong(id)
                            )
                            return getDataColumn(contentUri, null, null)
                        } catch (e: NumberFormatException) {
                            android.util.Log.w("MainActivity", "  Invalid download ID format: $id")
                            return null
                        }
                    }
                } else if ("content".equals(uri.scheme, ignoreCase = true)) {
                    android.util.Log.d("MainActivity", "  Content scheme, trying getDataColumn")
                    return getDataColumn(uri, null, null)
                } else if ("file".equals(uri.scheme, ignoreCase = true)) {
                    val path = uri.path
                    android.util.Log.d("MainActivity", "  File scheme, path: $path")
                    if (path != null && File(path).exists()) {
                        android.util.Log.d("MainActivity", "  ✓ File path exists: $path")
                        return path
                    }
                }
            } else {
                if ("content".equals(uri.scheme, ignoreCase = true)) {
                    return getDataColumn(uri, null, null)
                } else if ("file".equals(uri.scheme, ignoreCase = true)) {
                    val path = uri.path
                    if (path != null && File(path).exists()) {
                        return path
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Exception in getPathFromUri: ${e.message}")
            e.printStackTrace()
            return null
        }
        android.util.Log.w("MainActivity", "  Could not convert URI to path")
        return null
    }
    
    private fun getDataColumn(uri: Uri, selection: String?, selectionArgs: Array<String>?): String? {
        return try {
            var cursor = contentResolver.query(uri, arrayOf("_data"), selection, selectionArgs, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val columnIndex = it.getColumnIndex("_data")
                    if (columnIndex >= 0) {
                        val path = it.getString(columnIndex)
                        if (path != null && File(path).exists()) {
                            return path
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun scanForBooksWithUri(treeUri: Uri) {
        // For now, we can't easily scan DocumentFile URIs without major refactoring
        // Show a message and suggest using a path-based folder
        lifecycleScope.launch {
            // Try to get a display name from the URI
            val displayName = try {
                contentResolver.query(treeUri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && cursor.moveToFirst()) {
                        cursor.getString(nameIndex)
                    } else {
                        treeUri.lastPathSegment ?: "Selected folder"
                    }
                } ?: treeUri.lastPathSegment ?: "Selected folder"
            } catch (e: Exception) {
                treeUri.lastPathSegment ?: "Selected folder"
            }
            
            // Show info message
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Folder Selected")
                .setMessage("Folder selected: $displayName\n\nNote: Some folders may not be accessible via file paths. If no books appear, try selecting a folder from internal storage (like /storage/emulated/0/Music).")
                .setPositiveButton("OK") { _, _ -> }
                .show()
        }
    }
    
    private fun openPlayer(book: Book) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("book_path", book.folderPath)
            putExtra("book_name", book.name)
        }
        startActivity(intent)
    }
    
    inner class BookAdapter(
        private val onBookClick: (Book) -> Unit
    ) : RecyclerView.Adapter<BookAdapter.BookViewHolder>() {
        
        private var forceRefreshCovers = false
        
        fun setForceRefreshCovers(force: Boolean) {
            forceRefreshCovers = force
        }
        
        inner class BookViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val titleText: TextView = itemView.findViewById(R.id.bookTitle)
            val infoText: TextView = itemView.findViewById(R.id.bookInfo)
            val coverImage: android.widget.ImageView = itemView.findViewById(R.id.bookCover)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_book, parent, false)
            return BookViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
            val book = books[position]
            val displayName = bookNameStorage.getDisplayName(book.folderPath, book.name)
            holder.titleText.text = displayName
            holder.infoText.text = "${book.audioFiles.size} files"
            
            // Show progress if available
            progressStorage.getProgress(book.folderPath)?.let { progress ->
                val currentFile = book.audioFiles.getOrNull(progress.currentFileIndex)
                if (currentFile != null) {
                    holder.infoText.text = "File ${progress.currentFileIndex + 1}/${book.audioFiles.size} - ${currentFile.name}"
                }
            }
            
            // Load book cover (embedded art or online search)
            // Clear any existing image first
            holder.coverImage.setImageResource(android.R.color.transparent)
            holder.coverImage.setBackgroundResource(R.drawable.default_cover)
            
            lifecycleScope.launch {
                try {
                    android.util.Log.d("BookAdapter", "Loading cover for book: ${book.name}")
                    val cover = BookCoverFetcher.getBookCover(this@MainActivity, book, forceRefresh = forceRefreshCovers)
                    if (cover != null) {
                        android.util.Log.d("BookAdapter", "✓ Cover loaded for: ${book.name}")
                        holder.coverImage.setImageBitmap(cover)
                        holder.coverImage.background = null
                    } else {
                        android.util.Log.w("BookAdapter", "No cover found for: ${book.name}")
                        holder.coverImage.setImageResource(android.R.color.transparent)
                        holder.coverImage.setBackgroundResource(R.drawable.default_cover)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("BookAdapter", "Error loading cover: ${e.message}", e)
                    holder.coverImage.setImageResource(android.R.color.transparent)
                    holder.coverImage.setBackgroundResource(R.drawable.default_cover)
                }
            }
            
            holder.itemView.setOnClickListener {
                onBookClick(book)
            }
        }
        
        override fun getItemCount(): Int = books.size
    }
}

