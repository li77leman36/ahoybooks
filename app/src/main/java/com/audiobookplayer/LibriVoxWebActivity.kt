package com.audiobookplayer

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class LibriVoxWebActivity : AppCompatActivity() {
    
    private lateinit var webView: WebView
    private lateinit var folderPreferences: FolderPreferences
    private lateinit var downloadManager: DownloadManager
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Download completion handled by direct download, not needed here
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_librivox_web)
        
        folderPreferences = FolderPreferences(this)
        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        
        webView = findViewById(R.id.webView)
        setupWebView()
        
        // Register download receiver (for Android 13+ we need to specify export flag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
        
        // Check network connectivity before loading
        if (!isNetworkAvailable()) {
            android.widget.Toast.makeText(
                this,
                "No internet connection. Please check your network settings.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        
        // Load LibriVox website
        try {
            webView.loadUrl("https://librivox.org/")
        } catch (e: Exception) {
            Log.e("LibriVoxWebActivity", "Error loading URL: ${e.message}", e)
            android.widget.Toast.makeText(
                this,
                "Error loading page: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
    
    private fun setupWebView() {
        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT
        webSettings.userAgentString = webSettings.userAgentString + " AudiobookPlayer/1.0"
        
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                
                // Intercept ZIP file downloads from archive.org
                if (url.lowercase().endsWith(".zip") && url.contains("archive.org")) {
                    handleZipDownload(url)
                    return true
                }
                
                // Allow normal navigation
                return false
            }
            
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                val errorCode = error?.errorCode ?: -1
                val errorDescription = error?.description?.toString() ?: "Unknown error"
                Log.e("LibriVoxWebActivity", "WebView error code: $errorCode, description: $errorDescription")
                
                runOnUiThread {
                    val message = when (errorCode) {
                        WebViewClient.ERROR_HOST_LOOKUP -> {
                            "DNS error: Cannot resolve hostname. Please check your internet connection."
                        }
                        WebViewClient.ERROR_CONNECT -> {
                            "Connection error: Cannot connect to server. Please check your internet connection."
                        }
                        WebViewClient.ERROR_TIMEOUT -> {
                            "Connection timeout: Server took too long to respond."
                        }
                        else -> {
                            "Error loading page: $errorDescription"
                        }
                    }
                    android.widget.Toast.makeText(
                        this@LibriVoxWebActivity,
                        message,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                Log.e("LibriVoxWebActivity", "HTTP error: ${errorResponse?.statusCode}")
            }
            
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d("LibriVoxWebActivity", "Page started loading: $url")
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d("LibriVoxWebActivity", "Page finished loading: $url")
            }
        }
        
        webView.webChromeClient = WebChromeClient()
        
        // Handle download requests
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            // Intercept ZIP downloads from archive.org
            if (url.lowercase().endsWith(".zip") && url.contains("archive.org")) {
                handleZipDownload(url)
            } else {
                // For other files, use default download manager
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent)
                request.setDescription("Downloading file")
                request.setTitle(contentDisposition ?: "Download")
                request.allowScanningByMediaScanner()
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, contentDisposition ?: "download")
                downloadManager.enqueue(request)
            }
        }
        
        // Set up toolbar
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }
    
    private fun handleZipDownload(url: String) {
        Log.d("LibriVoxWebActivity", "Intercepting ZIP download: $url")
        
        // Create custom dialog with updatable message
        val messageView = android.widget.TextView(this).apply {
            text = "Preparing download..."
            setPadding(32, 16, 32, 16)
            textSize = 14f
        }
        
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Downloading")
            .setView(messageView)
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        lifecycleScope.launch {
            try {
                downloadAndExtractZip(url, progressDialog, messageView)
            } catch (e: Exception) {
                Log.e("LibriVoxWebActivity", "Error downloading ZIP: ${e.message}", e)
                runOnUiThread {
                    progressDialog.dismiss()
                    android.widget.Toast.makeText(
                        this@LibriVoxWebActivity,
                        "Download failed: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    
    private suspend fun downloadAndExtractZip(
        zipUrl: String,
        progressDialog: androidx.appcompat.app.AlertDialog,
        messageView: android.widget.TextView
    ) = withContext(Dispatchers.IO) {
        try {
            runOnUiThread {
                messageView.text = "Downloading ZIP file..."
            }
            
            // Get target folder
            val targetFolder = folderPreferences.getSelectedFolderPath()
                ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath
            
            if (targetFolder.startsWith("content://")) {
                throw Exception("Please select a folder path (not URI) for downloads")
            }
            
            val targetDir = File(targetFolder)
            if (!targetDir.exists() || !targetDir.canWrite()) {
                throw Exception("Target folder is not accessible: $targetFolder")
            }
            
            // Download ZIP file
            val zipFile = File(cacheDir, "librivox_download_${System.currentTimeMillis()}.zip")
            
            val connection = java.net.URL(zipUrl).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.setRequestProperty("User-Agent", "AudiobookPlayer/1.0")
            
            var totalBytes = connection.contentLength.toLong()
            var downloadedBytes = 0L
            
            connection.inputStream.use { input ->
                FileOutputStream(zipFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            val progress = (downloadedBytes * 100 / totalBytes).toInt()
                            runOnUiThread {
                                messageView.text = "Downloading... $progress%"
                            }
                        }
                    }
                }
            }
            
            runOnUiThread {
                messageView.text = "Extracting files..."
            }
            
            // Extract ZIP file
            val bookFolderName = extractBookNameFromUrl(zipUrl)
            val bookFolder = File(targetDir, bookFolderName)
            if (!bookFolder.exists()) {
                bookFolder.mkdirs()
            }
            
            var extractedCount = 0
            val buffer = ByteArray(8192)
            
            ZipInputStream(zipFile.inputStream()).use { zipInputStream ->
                var entry = zipInputStream.nextEntry
                
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val fileName = entry.name
                        // Only extract audio files
                        if (fileName.lowercase().endsWith(".mp3") ||
                            fileName.lowercase().endsWith(".m4b") ||
                            fileName.lowercase().endsWith(".m4a") ||
                            fileName.lowercase().endsWith(".ogg") ||
                            fileName.lowercase().endsWith(".flac")) {
                            
                            val outputFile = File(bookFolder, File(fileName).name)
                            outputFile.parentFile?.mkdirs()
                            
                            FileOutputStream(outputFile).use { output ->
                                var bytesRead: Int
                                while (zipInputStream.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                }
                            }
                            extractedCount++
                        }
                    }
                    zipInputStream.closeEntry()
                    entry = zipInputStream.nextEntry
                }
            }
            
            // Clean up ZIP file
            zipFile.delete()
            
            runOnUiThread {
                progressDialog.dismiss()
                android.widget.Toast.makeText(
                    this@LibriVoxWebActivity,
                    "Download complete! Extracted $extractedCount audio files.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                
                // Refresh book list in MainActivity if it exists
                setResult(RESULT_OK)
            }
        } catch (e: Exception) {
            runOnUiThread {
                progressDialog.dismiss()
                throw e
            }
        }
    }
    
    private fun extractBookNameFromUrl(url: String): String {
        // Try to extract book name from URL
        // Example: https://archive.org/download/bookname_64kb_mp3/bookname_64kb_mp3.zip
        val patterns = listOf(
            Regex("archive.org/download/([^/]+)/"),
            Regex("/([^/]+)\\.zip"),
            Regex("/([^/]+)_64kb_mp3\\.zip")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                var name = match.groupValues[1]
                // Clean up the name
                name = name.replace("_64kb_mp3", "")
                name = name.replace("_", " ")
                name = name.replace(Regex("[<>:\"/\\|?*]"), "_")
                return name.trim()
            }
        }
        
        // Fallback
        return "LibriVox Book ${System.currentTimeMillis()}"
    }
    
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // Ignore if receiver not registered
        }
    }
    
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                   capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            return networkInfo != null && networkInfo.isConnected
        }
    }
}

