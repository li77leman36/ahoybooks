package com.audiobookplayer

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import java.io.File

class BookScanner(private val context: Context) {
    
    private val audioExtensions = setOf("mp3", "m4a", "m4b", "aac", "ogg", "wav", "flac", "opus")
    
    fun getScanResult(basePath: String? = null): ScanResult {
        val baseDir = basePath?.let { File(it) } 
            ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        
        val folderExists = baseDir.exists()
        val folderReadable = baseDir.canRead()
        
        if (!folderExists || !folderReadable || !baseDir.isDirectory) {
            return ScanResult(
                folderPath = baseDir.absolutePath,
                folderExists = folderExists,
                folderReadable = folderReadable,
                totalItems = 0,
                totalFiles = 0,
                audioFiles = 0,
                subfolders = 0,
                sampleFiles = emptyList(),
                sampleAudioFiles = emptyList(),
                errorMessage = if (!folderExists) "Folder does not exist" 
                              else if (!folderReadable) "Folder is not readable" 
                              else "Path is not a directory"
            )
        }
        
        val baseDirFiles = try {
            baseDir.listFiles()
        } catch (e: SecurityException) {
            return ScanResult(
                folderPath = baseDir.absolutePath,
                folderExists = true,
                folderReadable = true,
                totalItems = 0,
                totalFiles = 0,
                audioFiles = 0,
                subfolders = 0,
                sampleFiles = emptyList(),
                sampleAudioFiles = emptyList(),
                errorMessage = "SecurityException: ${e.message}"
            )
        } catch (e: Exception) {
            return ScanResult(
                folderPath = baseDir.absolutePath,
                folderExists = true,
                folderReadable = true,
                totalItems = 0,
                totalFiles = 0,
                audioFiles = 0,
                subfolders = 0,
                sampleFiles = emptyList(),
                sampleAudioFiles = emptyList(),
                errorMessage = "Error: ${e.message}"
            )
        }
        
        val allFiles = baseDirFiles?.filter { it.isFile } ?: emptyList()
        val allDirs = baseDirFiles?.filter { it.isDirectory } ?: emptyList()
        val audioFiles = allFiles.filter { isAudioFile(it) }
        
        return ScanResult(
            folderPath = baseDir.absolutePath,
            folderExists = true,
            folderReadable = true,
            totalItems = baseDirFiles?.size ?: 0,
            totalFiles = allFiles.size,
            audioFiles = audioFiles.size,
            subfolders = allDirs.size,
            sampleFiles = allFiles.take(10).map { "${it.name} (${it.extension})" },
            sampleAudioFiles = audioFiles.take(10).map { it.name },
            errorMessage = null
        )
    }
    
    fun scanForBooks(basePath: String? = null): List<Book> {
        val books = mutableListOf<Book>()
        val baseDir = basePath?.let { File(it) } 
            ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        
        if (!baseDir.exists()) {
            android.util.Log.w("BookScanner", "Directory does not exist: ${baseDir.absolutePath}")
            return books
        }
        
        if (!baseDir.isDirectory) {
            android.util.Log.w("BookScanner", "Path is not a directory: ${baseDir.absolutePath}")
            return books
        }
        
        if (!baseDir.canRead()) {
            android.util.Log.w("BookScanner", "Cannot read directory: ${baseDir.absolutePath}")
            return books
        }
        
        android.util.Log.d("BookScanner", "Scanning directory: ${baseDir.absolutePath}")
        
        // First, check if the base directory itself contains audio files (it's a book folder)
        val baseDirFiles = try {
            baseDir.listFiles()
        } catch (e: SecurityException) {
            android.util.Log.e("BookScanner", "SecurityException accessing directory: ${e.message}")
            return books
        } catch (e: Exception) {
            android.util.Log.e("BookScanner", "Error accessing directory: ${e.message}")
            return books
        }
        
        // Log all files found for debugging
        val allFiles = baseDirFiles?.filter { it.isFile } ?: emptyList()
        android.util.Log.d("BookScanner", "=== SCANNING DIRECTORY: ${baseDir.absolutePath} ===")
        android.util.Log.d("BookScanner", "Found ${allFiles.size} files in base directory")
        
        if (allFiles.isEmpty()) {
            android.util.Log.w("BookScanner", "⚠ No files found in directory!")
            val allItems = baseDirFiles?.map { 
                "${it.name} (${if (it.isDirectory) "DIR" else "FILE"})"
            } ?: emptyList()
            android.util.Log.d("BookScanner", "All items in directory (${allItems.size}): $allItems")
        } else {
            android.util.Log.d("BookScanner", "Files found (showing first 30):")
            allFiles.take(30).forEach { file ->
                val ext = try { file.extension } catch (e: Exception) { "ERROR" }
                val isAudio = isAudioFile(file)
                val size = file.length()
                val canRead = file.canRead()
                android.util.Log.d("BookScanner", "  📄 '${file.name}' | ext:'$ext' | audio:$isAudio | size:$size | readable:$canRead")
            }
            
            // Count MP3 files specifically
            val mp3Files = allFiles.filter { 
                val name = it.name.lowercase()
                name.endsWith(".mp3") || it.extension.lowercase() == "mp3"
            }
            android.util.Log.d("BookScanner", "MP3 files found: ${mp3Files.size}")
            mp3Files.take(10).forEach { file ->
                android.util.Log.d("BookScanner", "  🎵 MP3: ${file.name}")
            }
        }
        
        val baseDirAudioFiles = baseDirFiles
            ?.filter { it.isFile && isAudioFile(it) }
            ?.sortedWith(Comparator { a, b -> 
                val aKey = extractSortNumber(a.name)
                val bKey = extractSortNumber(b.name)
                when {
                    aKey.first != bKey.first -> aKey.first.compareTo(bKey.first)
                    aKey.second != bKey.second -> aKey.second.compareTo(bKey.second)
                    else -> aKey.third.compareTo(bKey.third)
                }
            })
            ?: emptyList()
        
        android.util.Log.d("BookScanner", "Found ${baseDirAudioFiles.size} audio files in base directory")
        
        if (baseDirAudioFiles.isNotEmpty()) {
            // The selected folder is itself a book
            android.util.Log.d("BookScanner", "Found book in base directory: ${baseDir.name} with ${baseDirAudioFiles.size} files")
            baseDirAudioFiles.forEach { file ->
                android.util.Log.d("BookScanner", "  Audio file: ${file.name}")
            }
            books.add(Book(
                name = baseDir.name,
                folderPath = baseDir.absolutePath,
                audioFiles = baseDirAudioFiles.map { AudioFile(file = it, uri = null) }
            ))
            return books
        }
        
        // Otherwise, scan for folders that contain audio files (subfolders are books)
        val subfolders = baseDirFiles?.filter { it.isDirectory } ?: emptyList()
        android.util.Log.d("BookScanner", "Found ${subfolders.size} subfolders to scan")
        
        baseDirFiles?.forEach { folder ->
            if (folder.isDirectory) {
                try {
                    android.util.Log.d("BookScanner", "Scanning subfolder: ${folder.name}")
                    val folderFiles = folder.listFiles()
                    val allFolderFiles = folderFiles?.filter { it.isFile } ?: emptyList()
                    android.util.Log.d("BookScanner", "  Found ${allFolderFiles.size} files in ${folder.name}")
                    
                    val audioFiles = folderFiles
                        ?.filter { it.isFile && isAudioFile(it) }
                        ?.sortedWith(Comparator { a, b -> 
                            val aKey = extractSortNumber(a.name)
                            val bKey = extractSortNumber(b.name)
                            when {
                                aKey.first != bKey.first -> aKey.first.compareTo(bKey.first)
                                aKey.second != bKey.second -> aKey.second.compareTo(bKey.second)
                                else -> aKey.third.compareTo(bKey.third)
                            }
                        })
                        ?: emptyList()
                    
                    android.util.Log.d("BookScanner", "  Found ${audioFiles.size} audio files in ${folder.name}")
                    if (audioFiles.isEmpty() && allFolderFiles.isNotEmpty()) {
                        // Log first few files to see why they're not being recognized
                        allFolderFiles.take(5).forEach { file ->
                            android.util.Log.d("BookScanner", "    Non-audio file: ${file.name}, extension: '${file.extension}'")
                        }
                    }
                    
                    if (audioFiles.isNotEmpty()) {
                        android.util.Log.d("BookScanner", "Found book: ${folder.name} with ${audioFiles.size} files")
                        books.add(Book(
                            name = folder.name,
                            folderPath = folder.absolutePath,
                            audioFiles = audioFiles.map { AudioFile(file = it, uri = null) }
                        ))
                    }
                } catch (e: SecurityException) {
                    android.util.Log.w("BookScanner", "SecurityException scanning folder ${folder.name}: ${e.message}")
                } catch (e: Exception) {
                    android.util.Log.w("BookScanner", "Error scanning folder ${folder.name}: ${e.message}")
                }
            }
        }
        
        android.util.Log.d("BookScanner", "Scan complete. Found ${books.size} books")
        return books.sortedBy { it.name }
    }
    
    private fun isAudioFile(file: File): Boolean {
        val fileName = file.name
        if (fileName.isEmpty()) {
            android.util.Log.w("BookScanner", "Empty filename")
            return false
        }
        
        // Try multiple methods to get extension
        var extension: String? = null
        
        // Method 1: Use File.extension (Kotlin extension property)
        try {
            extension = file.extension.lowercase().trim()
            if (extension.isNotEmpty()) {
                android.util.Log.d("BookScanner", "File '$fileName' - extension from .extension: '$extension'")
            }
        } catch (e: Exception) {
            android.util.Log.w("BookScanner", "Error getting extension: ${e.message}")
        }
        
        // Method 2: Manual extraction if .extension didn't work
        if (extension.isNullOrEmpty()) {
            val lastDot = fileName.lastIndexOf('.')
            if (lastDot >= 0 && lastDot < fileName.length - 1) {
                extension = fileName.substring(lastDot + 1).lowercase().trim()
                android.util.Log.d("BookScanner", "File '$fileName' - extension from substring: '$extension'")
            }
        }
        
        if (extension.isNullOrEmpty()) {
            android.util.Log.d("BookScanner", "File has no extension: '$fileName'")
            return false
        }
        
        // Check if it's in our audio extensions list
        val isAudio = audioExtensions.contains(extension)
        
        // Special logging for MP3 files
        if (extension == "mp3") {
            android.util.Log.d("BookScanner", "MP3 file detected: '$fileName' - isAudio: $isAudio")
        }
        
        if (!isAudio) {
            android.util.Log.v("BookScanner", "File '$fileName' has extension '$extension' which is not in audio list: $audioExtensions")
        } else {
            android.util.Log.d("BookScanner", "✓ Audio file recognized: '$fileName' (extension: '$extension')")
        }
        
        return isAudio
    }
    
    fun scanCustomFolder(folderPath: String): Book? {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) {
            return null
        }
        
        val audioFiles = folder.listFiles()
            ?.filter { it.isFile && isAudioFile(it) }
            ?.sortedWith(Comparator { a, b -> 
                val aKey = extractSortNumber(a.name)
                val bKey = extractSortNumber(b.name)
                when {
                    aKey.first != bKey.first -> aKey.first.compareTo(bKey.first)
                    aKey.second != bKey.second -> aKey.second.compareTo(bKey.second)
                    else -> aKey.third.compareTo(bKey.third)
                }
            })
            ?: emptyList()
        
        return if (audioFiles.isNotEmpty()) {
            Book(
                name = folder.name,
                folderPath = folder.absolutePath,
                audioFiles = audioFiles.map { AudioFile(file = it, uri = null) }
            )
        } else {
            null
        }
    }
    
    fun scanForBooksWithUri(context: Context, treeUri: Uri): List<Book> {
        val books = mutableListOf<Book>()
        
        try {
            val rootDir = DocumentFile.fromTreeUri(context, treeUri)
            if (rootDir == null || !rootDir.exists() || !rootDir.isDirectory) {
                android.util.Log.e("BookScanner", "Invalid or inaccessible DocumentFile URI: $treeUri")
                return books
            }
            
            android.util.Log.d("BookScanner", "Scanning DocumentFile URI: $treeUri")
            android.util.Log.d("BookScanner", "  Name: ${rootDir.name}")
            android.util.Log.d("BookScanner", "  Can read: ${rootDir.canRead()}")
            
            // List all files and directories
            val children = rootDir.listFiles()
            android.util.Log.d("BookScanner", "Found ${children.size} items in selected folder")
            
            // Log all items for debugging
            children.forEach { item ->
                android.util.Log.d("BookScanner", "  Item: ${item.name}, isFile: ${item.isFile}, isDirectory: ${item.isDirectory}")
            }
            
            // Check if the root directory itself contains audio files (it's a book)
            val rootAudioFiles = children.filter { 
                val isFile = it.isFile
                val hasName = it.name != null
                val isAudio = if (hasName) isAudioFileByName(it.name!!) else false
                android.util.Log.d("BookScanner", "  Checking: ${it.name}, isFile=$isFile, hasName=$hasName, isAudio=$isAudio")
                isFile && hasName && isAudio
            }
            
            android.util.Log.d("BookScanner", "Found ${rootAudioFiles.size} audio files in root directory")
            
            if (rootAudioFiles.isNotEmpty()) {
                android.util.Log.d("BookScanner", "Root directory contains ${rootAudioFiles.size} audio files - treating as single book")
                val sortedAudioFiles = rootAudioFiles.sortedWith(Comparator { a, b -> 
                    val aKey = extractSortNumber(a.name ?: "")
                    val bKey = extractSortNumber(b.name ?: "")
                    when {
                        aKey.first != bKey.first -> aKey.first.compareTo(bKey.first)
                        aKey.second != bKey.second -> aKey.second.compareTo(bKey.second)
                        else -> aKey.third.compareTo(bKey.third)
                    }
                })
                val audioFileList = sortedAudioFiles.mapNotNull { docFile ->
                    try {
                        val uri = docFile.uri
                        // Try to get a file path, but fall back to URI-based access
                        val filePath = try {
                            getPathFromUri(context, uri)
                        } catch (e: Exception) {
                            null
                        }
                        
                        val file = if (filePath != null && File(filePath).exists() && File(filePath).canRead()) {
                            File(filePath)
                        } else {
                            null
                        }
                        
                        // Always create AudioFile with URI, even if we have a file path
                        AudioFile(file = file, uri = uri)
                    } catch (e: Exception) {
                        android.util.Log.e("BookScanner", "Error processing file ${docFile.name}: ${e.message}")
                        null
                    }
                }
                
                if (audioFileList.isNotEmpty()) {
                    books.add(Book(
                        name = rootDir.name ?: "Unknown Book",
                        folderPath = treeUri.toString(), // Store URI as path for now
                        audioFiles = audioFileList
                    ))
                }
            }
            
            // Scan subdirectories as separate books
            val subdirs = children.filter { it.isDirectory }
            android.util.Log.d("BookScanner", "Found ${subdirs.size} subdirectories to scan")
            
            for (subdir in subdirs) {
                android.util.Log.d("BookScanner", "Scanning subdirectory: ${subdir.name}")
                val allSubdirFiles = subdir.listFiles()
                android.util.Log.d("BookScanner", "  Found ${allSubdirFiles.size} items in ${subdir.name}")
                
                val subdirFiles = allSubdirFiles.filter { 
                    val isFile = it.isFile
                    val hasName = it.name != null
                    val isAudio = if (hasName) isAudioFileByName(it.name!!) else false
                    if (hasName) {
                        android.util.Log.d("BookScanner", "    File: ${it.name}, isFile=$isFile, isAudio=$isAudio")
                    }
                    isFile && hasName && isAudio
                }
                
                android.util.Log.d("BookScanner", "  Found ${subdirFiles.size} audio files in ${subdir.name}")
                
                if (subdirFiles.isNotEmpty()) {
                    android.util.Log.d("BookScanner", "Subdirectory '${subdir.name}' contains ${subdirFiles.size} audio files")
                    val sortedSubdirFiles = subdirFiles.sortedWith(Comparator { a, b -> 
                        val aKey = extractSortNumber(a.name ?: "")
                        val bKey = extractSortNumber(b.name ?: "")
                        when {
                            aKey.first != bKey.first -> aKey.first.compareTo(bKey.first)
                            aKey.second != bKey.second -> aKey.second.compareTo(bKey.second)
                            else -> aKey.third.compareTo(bKey.third)
                        }
                    })
                    val audioFileList = sortedSubdirFiles.mapNotNull { docFile ->
                        try {
                            val uri = docFile.uri
                            val filePath = try {
                                getPathFromUri(context, uri)
                            } catch (e: Exception) {
                                null
                            }
                            
                            val file = if (filePath != null && File(filePath).exists() && File(filePath).canRead()) {
                                File(filePath)
                            } else {
                                null
                            }
                            
                            // Always create AudioFile with URI, even if we have a file path
                            AudioFile(file = file, uri = uri)
                        } catch (e: Exception) {
                            android.util.Log.e("BookScanner", "Error processing file ${docFile.name}: ${e.message}")
                            null
                        }
                    }
                    
                    if (audioFileList.isNotEmpty()) {
                        books.add(Book(
                            name = subdir.name ?: "Unknown Book",
                            folderPath = subdir.uri.toString(),
                            audioFiles = audioFileList
                        ))
                    }
                }
            }
            
            android.util.Log.d("BookScanner", "Found ${books.size} books via URI scanning")
        } catch (e: Exception) {
            android.util.Log.e("BookScanner", "Error scanning with URI: ${e.message}", e)
        }
        
        return books
    }
    
    private fun isAudioFileByName(fileName: String): Boolean {
        if (fileName.isEmpty()) {
            android.util.Log.v("BookScanner", "Empty filename")
            return false
        }
        
        val lastDot = fileName.lastIndexOf('.')
        if (lastDot < 0 || lastDot >= fileName.length - 1) {
            android.util.Log.v("BookScanner", "File '$fileName' has no extension")
            return false
        }
        
        val extension = fileName.substring(lastDot + 1).lowercase().trim()
        val isAudio = audioExtensions.contains(extension)
        
        if (!isAudio) {
            android.util.Log.v("BookScanner", "File '$fileName' has extension '$extension' which is not in audio list: $audioExtensions")
        } else {
            android.util.Log.d("BookScanner", "✓ Audio file recognized: '$fileName' (extension: '$extension')")
        }
        
        return isAudio
    }
    
    private fun getPathFromUri(context: Context, uri: Uri): String? {
        // Try to get file path from URI
        try {
            android.util.Log.d("BookScanner", "getPathFromUri - URI: $uri, scheme: ${uri.scheme}")
            
            if (uri.scheme == "file") {
                val path = uri.path
                android.util.Log.d("BookScanner", "File URI, path: $path")
                return path
            }
            
            // For content URIs, try DocumentsContract
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                if (android.provider.DocumentsContract.isDocumentUri(context, uri)) {
                    val docId = android.provider.DocumentsContract.getDocumentId(uri)
                    android.util.Log.d("BookScanner", "Document URI, docId: $docId")
                    
                    if (docId.startsWith("primary:")) {
                        val pathPart = docId.substring("primary:".length)
                        val storageDir = Environment.getExternalStorageDirectory()
                        val fullPath = File(storageDir, pathPart).absolutePath
                        android.util.Log.d("BookScanner", "Converted to path: $fullPath")
                        return fullPath
                    } else if (docId.contains(":")) {
                        // Handle other storage providers (e.g., "1A2B-3C4D:path/to/file")
                        val split = docId.split(":", limit = 2)
                        if (split.size == 2) {
                            val storageId = split[0]
                            val pathPart = split[1]
                            // Try to find the storage
                            val storageDir = File("/storage/$storageId")
                            if (storageDir.exists()) {
                                val fullPath = File(storageDir, pathPart).absolutePath
                                android.util.Log.d("BookScanner", "Converted to path: $fullPath")
                                return fullPath
                            }
                        }
                    }
                } else if (android.provider.DocumentsContract.isTreeUri(uri)) {
                    // Tree URI - get the root path
                    val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                    android.util.Log.d("BookScanner", "Tree URI, treeDocId: $treeDocId")
                    
                    if (treeDocId.startsWith("primary:")) {
                        val pathPart = treeDocId.substring("primary:".length)
                        val storageDir = Environment.getExternalStorageDirectory()
                        val fullPath = File(storageDir, pathPart).absolutePath
                        android.util.Log.d("BookScanner", "Tree converted to path: $fullPath")
                        return fullPath
                    }
                }
            }
            
            // Try direct path extraction for content:// URIs
            val path = uri.path
            if (path != null) {
                android.util.Log.d("BookScanner", "URI path: $path")
                // Check if it looks like a file path
                if (path.startsWith("/storage/") || path.startsWith("/sdcard/")) {
                    return path
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BookScanner", "Error getting path from URI: ${e.message}", e)
        }
        
        android.util.Log.w("BookScanner", "Could not convert URI to path: $uri")
        return null
    }
    
    /**
     * Extracts a numeric value from a filename for sorting purposes.
     * Looks for patterns like:
     * - Numbers: 1, 2, 3, 10, 20, etc.
     * - Chapter markers: "chapter 1", "chapter 2", "ch 1", "ch. 1", "ch1", etc.
     * - Part markers: "part 1", "part 2", etc.
     * - Track markers: "track 1", "track 2", etc.
     * 
     * Returns a sort key: (primaryNumber, secondaryNumber, originalName)
     * This ensures proper numeric sorting (1, 2, 10, 20 instead of 1, 10, 2, 20)
     */
    private fun extractSortNumber(fileName: String): Triple<Int, Int, String> {
        val lowerName = fileName.lowercase()
        
        // Try to find chapter/part/track markers first
        val patterns = listOf(
            Regex("(?:chapter|ch\\.?|ch)\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("(?:part|pt\\.?|pt)\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("(?:track|tr\\.?|tr)\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("(?:disc|disk|cd)\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("(?:book|bk\\.?|bk)\\s*(\\d+)", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            val match = pattern.find(lowerName)
            if (match != null) {
                val number = match.groupValues[1].toIntOrNull()
                if (number != null) {
                    return Triple(number, 0, fileName)
                }
            }
        }
        
        // Look for numbers at the start of the filename (e.g., "01 - Title.mp3", "1 Title.mp3")
        val startNumberMatch = Regex("^\\s*(\\d+)").find(fileName)
        if (startNumberMatch != null) {
            val number = startNumberMatch.groupValues[1].toIntOrNull()
            if (number != null) {
                return Triple(number, 0, fileName)
            }
        }
        
        // Look for numbers in the filename (prefer larger numbers, likely to be chapter/track numbers)
        val allNumbers = Regex("\\d+").findAll(fileName).mapNotNull { it.value.toIntOrNull() }.toList()
        if (allNumbers.isNotEmpty()) {
            // Use the largest number found (likely the chapter/track number)
            val maxNumber = allNumbers.maxOrNull() ?: 0
            // Use the first number as secondary for tie-breaking
            val firstNumber = allNumbers.firstOrNull() ?: 0
            return Triple(maxNumber, firstNumber, fileName)
        }
        
        // No numbers found, sort alphabetically
        return Triple(Int.MAX_VALUE, 0, fileName)
    }
}

