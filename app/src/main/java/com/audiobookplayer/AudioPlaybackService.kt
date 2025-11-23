package com.audiobookplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

class AudioPlaybackService : LifecycleService() {
    
    private val binder = LocalBinder()
    private var mediaPlayer: MediaPlayer? = null
    private var currentBook: Book? = null
    private var currentFileIndex = 0
    private var progressUpdateJob: Job? = null
    private val progressStorage: ProgressStorage by lazy { ProgressStorage(this) }
    
    // Queue management
    private val trackQueue = mutableListOf<Int>() // List of file indices in queue order
    private var queueCurrentIndex = 0 // Current position in queue
    
    private var playbackStateListener: PlaybackStateListener? = null
    
    interface PlaybackStateListener {
        fun onProgressChanged(position: Int, duration: Int)
        fun onFileChanged(fileIndex: Int, fileName: String)
        fun onPlaybackStateChanged(isPlaying: Boolean)
        fun onPlaybackError(message: String) {
            // Optional default implementation
            android.util.Log.e("AudioPlaybackService", "Playback error: $message")
        }
        fun onQueueChanged(queue: List<Int>, currentIndex: Int) {
            // Optional default implementation for queue updates
        }
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): AudioPlaybackService = this@AudioPlaybackService
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }
    
    fun setPlaybackStateListener(listener: PlaybackStateListener?) {
        playbackStateListener = listener
    }
    
    fun loadBook(book: Book, resumeFromProgress: Boolean = true) {
        try {
            if (book.audioFiles.isEmpty()) {
                val errorMsg = "Book '${book.name}' has no audio files"
                android.util.Log.e("AudioPlaybackService", errorMsg)
                playbackStateListener?.onPlaybackError(errorMsg)
                return
            }
            
            android.util.Log.d("AudioPlaybackService", "Loading book: ${book.name} (${book.audioFiles.size} files)")
            currentBook = book
            
            // Initialize queue - restore saved queue or use default order
            if (resumeFromProgress) {
                val savedProgress = progressStorage.getProgress(book.folderPath)
                if (savedProgress != null) {
                    val fileIndex = savedProgress.currentFileIndex.coerceIn(0, book.audioFiles.size - 1)
                    val position = savedProgress.currentPosition.coerceAtLeast(0)
                    
                    // Restore saved queue if available and valid
                    val savedQueue = savedProgress.queueOrder
                    if (savedQueue != null && 
                        savedQueue.isNotEmpty() && 
                        savedQueue.all { it in book.audioFiles.indices } &&
                        savedQueue.size == book.audioFiles.size) {
                        trackQueue.clear()
                        trackQueue.addAll(savedQueue)
                        queueCurrentIndex = (savedProgress.queueCurrentIndex ?: 0).coerceIn(0, trackQueue.size - 1)
                        android.util.Log.d("AudioPlaybackService", "Restored saved queue with ${trackQueue.size} tracks, current index: $queueCurrentIndex")
                    } else {
                        // Invalid or missing saved queue, use default order
                        trackQueue.clear()
                        trackQueue.addAll(book.audioFiles.indices)
                        queueCurrentIndex = trackQueue.indexOf(fileIndex).coerceAtLeast(0)
                        android.util.Log.d("AudioPlaybackService", "Saved queue invalid or missing, using default order")
                    }
                    
                    android.util.Log.d("AudioPlaybackService", "Resuming from file $fileIndex, position $position")
                    currentFileIndex = fileIndex
                    loadFile(currentFileIndex, position)
                    notifyQueueChanged()
                    return
                }
            }
            
            // No saved progress - initialize with default order
            trackQueue.clear()
            trackQueue.addAll(book.audioFiles.indices)
            currentFileIndex = 0
            queueCurrentIndex = 0
            loadFile(0, 0)
            notifyQueueChanged()
        } catch (e: Exception) {
            android.util.Log.e("AudioPlaybackService", "Exception in loadBook(): ${e.message}", e)
            playbackStateListener?.onPlaybackError("Error loading book: ${e.message}")
        }
    }
    
    private fun loadFile(fileIndex: Int, startPosition: Int = 0) {
        val book = currentBook ?: return
        
        if (fileIndex < 0 || fileIndex >= book.audioFiles.size) {
            android.util.Log.e("AudioPlaybackService", "Invalid file index: $fileIndex (book has ${book.audioFiles.size} files)")
            return
        }
        
        releaseMediaPlayer()
        
        val audioFile = book.audioFiles[fileIndex]
        currentFileIndex = fileIndex
        
        android.util.Log.d("AudioPlaybackService", "Loading file: ${audioFile.name}")
        android.util.Log.d("AudioPlaybackService", "  Has file: ${audioFile.file != null}")
        android.util.Log.d("AudioPlaybackService", "  Has URI: ${audioFile.uri != null}")
        android.util.Log.d("AudioPlaybackService", "  File exists: ${audioFile.exists}")
        android.util.Log.d("AudioPlaybackService", "  File readable: ${audioFile.canRead}")
        android.util.Log.d("AudioPlaybackService", "  File size: ${audioFile.length} bytes")
        
        // Check if we can access the file
        val canAccess = if (audioFile.file != null) {
            audioFile.exists && audioFile.canRead
        } else if (audioFile.uri != null) {
            true // URI-based files are accessible via content resolver
        } else {
            false
        }
        
        if (!canAccess) {
            val errorMsg = "File is not accessible: ${audioFile.name}"
            android.util.Log.e("AudioPlaybackService", errorMsg)
            playbackStateListener?.onPlaybackError(errorMsg)
            playbackStateListener?.onFileChanged(fileIndex, "ERROR: Cannot access file")
            return
        }
        
        try {
            mediaPlayer = MediaPlayer().apply {
                try {
                    // Use file path if available, otherwise use URI
                    if (audioFile.file != null && audioFile.file.exists() && audioFile.file.canRead()) {
                        android.util.Log.d("AudioPlaybackService", "Using file path: ${audioFile.file.absolutePath}")
                        setDataSource(audioFile.file.absolutePath)
                    } else if (audioFile.uri != null) {
                        android.util.Log.d("AudioPlaybackService", "Using URI: ${audioFile.uri}")
                        val contentResolver = this@AudioPlaybackService.contentResolver
                        val fileDescriptor = contentResolver.openFileDescriptor(audioFile.uri, "r")
                        if (fileDescriptor != null) {
                            setDataSource(fileDescriptor.fileDescriptor)
                            fileDescriptor.close()
                        } else {
                            throw IOException("Could not open file descriptor for URI: ${audioFile.uri}")
                        }
                    } else {
                        throw IOException("No file path or URI available")
                    }
                    android.util.Log.d("AudioPlaybackService", "DataSource set, preparing...")
                    prepare()
                    android.util.Log.d("AudioPlaybackService", "MediaPlayer prepared, duration: $duration ms")
                    seekTo(startPosition)
                    setOnCompletionListener {
                        android.util.Log.d("AudioPlaybackService", "File completed")
                        onFileComplete()
                    }
                    setOnErrorListener { _, what, extra ->
                        val errorMsg = "MediaPlayer error: what=$what, extra=$extra, file=${audioFile.name}"
                        android.util.Log.e("AudioPlaybackService", errorMsg)
                        playbackStateListener?.onPlaybackError(errorMsg)
                        playbackStateListener?.onFileChanged(fileIndex, "ERROR: Playback failed")
                        releaseMediaPlayer()
                        false
                    }
                    setOnPreparedListener {
                        android.util.Log.d("AudioPlaybackService", "MediaPlayer prepared successfully")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AudioPlaybackService", "Error setting up MediaPlayer: ${e.message}", e)
                    release()
                    mediaPlayer = null
                    throw e
                }
            }
            
            playbackStateListener?.onFileChanged(fileIndex, audioFile.name)
            updateProgress()
            // Ensure notification is shown as foreground
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: IOException) {
            val errorMsg = "Cannot load audio file: ${audioFile.name}\n${e.message}"
            android.util.Log.e("AudioPlaybackService", "IOException loading file: ${audioFile.absolutePath}", e)
            playbackStateListener?.onPlaybackError(errorMsg)
            playbackStateListener?.onFileChanged(fileIndex, "ERROR: ${e.message}")
            releaseMediaPlayer()
        } catch (e: IllegalStateException) {
            val errorMsg = "MediaPlayer error: ${audioFile.name}\n${e.message}"
            android.util.Log.e("AudioPlaybackService", "IllegalStateException loading file: ${audioFile.absolutePath}", e)
            playbackStateListener?.onPlaybackError(errorMsg)
            playbackStateListener?.onFileChanged(fileIndex, "ERROR: ${e.message}")
            releaseMediaPlayer()
        } catch (e: Exception) {
            val errorMsg = "Unexpected error loading file: ${audioFile.name}\n${e.message}"
            android.util.Log.e("AudioPlaybackService", "Exception loading file: ${audioFile.absolutePath}", e)
            playbackStateListener?.onPlaybackError(errorMsg)
            playbackStateListener?.onFileChanged(fileIndex, "ERROR: ${e.message}")
            releaseMediaPlayer()
        }
    }
    
    fun play() {
        try {
            mediaPlayer?.let {
                if (!it.isPlaying) {
                    it.start()
                    startProgressUpdates()
                    playbackStateListener?.onPlaybackStateChanged(true)
                    updateNotification()
                }
            } ?: run {
                android.util.Log.w("AudioPlaybackService", "Cannot play: MediaPlayer is null")
            }
        } catch (e: IllegalStateException) {
            android.util.Log.e("AudioPlaybackService", "IllegalStateException in play(): ${e.message}", e)
            playbackStateListener?.onPlaybackError("Cannot start playback: ${e.message}")
        } catch (e: Exception) {
            android.util.Log.e("AudioPlaybackService", "Exception in play(): ${e.message}", e)
            playbackStateListener?.onPlaybackError("Playback error: ${e.message}")
        }
    }
    
    fun pause() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                    stopProgressUpdates()
                    saveProgress()
                    playbackStateListener?.onPlaybackStateChanged(false)
                    updateNotification()
                }
            } ?: run {
                android.util.Log.w("AudioPlaybackService", "Cannot pause: MediaPlayer is null")
            }
        } catch (e: IllegalStateException) {
            android.util.Log.e("AudioPlaybackService", "IllegalStateException in pause(): ${e.message}", e)
        } catch (e: Exception) {
            android.util.Log.e("AudioPlaybackService", "Exception in pause(): ${e.message}", e)
        }
    }
    
    fun isPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying ?: false
        } catch (e: Exception) {
            android.util.Log.e("AudioPlaybackService", "Exception in isPlaying(): ${e.message}", e)
            false
        }
    }
    
    fun seekTo(position: Int) {
        try {
            mediaPlayer?.seekTo(position)
            updateProgress()
        } catch (e: IllegalStateException) {
            android.util.Log.e("AudioPlaybackService", "IllegalStateException in seekTo(): ${e.message}", e)
        } catch (e: Exception) {
            android.util.Log.e("AudioPlaybackService", "Exception in seekTo(): ${e.message}", e)
        }
    }
    
    fun rewind() {
        try {
            mediaPlayer?.let { mp ->
                val currentPos = try { mp.currentPosition } catch (e: Exception) { 0 }
                val newPosition = (currentPos - BookProgress.REWIND_MS).coerceAtLeast(0)
                mp.seekTo(newPosition)
                updateProgress()
            } ?: run {
                android.util.Log.w("AudioPlaybackService", "Cannot rewind: MediaPlayer is null")
            }
        } catch (e: IllegalStateException) {
            android.util.Log.e("AudioPlaybackService", "IllegalStateException in rewind(): ${e.message}", e)
        } catch (e: Exception) {
            android.util.Log.e("AudioPlaybackService", "Exception in rewind(): ${e.message}", e)
        }
    }
    
    fun forward() {
        try {
            mediaPlayer?.let { mp ->
                val currentPos = try { mp.currentPosition } catch (e: Exception) { 0 }
                val duration = try { mp.duration } catch (e: Exception) { 0 }
                val newPosition = (currentPos + BookProgress.FORWARD_MS).coerceAtMost(duration)
                mp.seekTo(newPosition)
                updateProgress()
            } ?: run {
                android.util.Log.w("AudioPlaybackService", "Cannot forward: MediaPlayer is null")
            }
        } catch (e: IllegalStateException) {
            android.util.Log.e("AudioPlaybackService", "IllegalStateException in forward(): ${e.message}", e)
        } catch (e: Exception) {
            android.util.Log.e("AudioPlaybackService", "Exception in forward(): ${e.message}", e)
        }
    }
    
    fun nextTrack() {
        if (queueCurrentIndex < trackQueue.size - 1) {
            moveToQueuePosition(queueCurrentIndex + 1)
        }
    }
    
    fun previousTrack() {
        if (queueCurrentIndex > 0) {
            moveToQueuePosition(queueCurrentIndex - 1)
        } else {
            // If at start, restart current track
            seekTo(0)
        }
    }
    
    fun getCurrentPosition(): Int {
        return try {
            mediaPlayer?.currentPosition ?: 0
        } catch (e: Exception) {
            android.util.Log.e("AudioPlaybackService", "Exception in getCurrentPosition(): ${e.message}", e)
            0
        }
    }
    
    fun getDuration(): Int {
        return mediaPlayer?.duration ?: 0
    }
    
    fun getCurrentFileIndex(): Int {
        return currentFileIndex
    }
    
    fun getCurrentBook(): Book? {
        return currentBook
    }
    
    // Queue management methods
    fun getQueue(): List<Int> {
        return trackQueue.toList()
    }
    
    fun getQueueCurrentIndex(): Int {
        return queueCurrentIndex
    }
    
    fun moveToQueuePosition(queueIndex: Int) {
        if (queueIndex >= 0 && queueIndex < trackQueue.size) {
            queueCurrentIndex = queueIndex
            currentFileIndex = trackQueue[queueCurrentIndex]
            val wasPlaying = isPlaying()
            loadFile(currentFileIndex, 0)
            if (wasPlaying) {
                play()
            }
            notifyQueueChanged()
            saveProgress() // Save queue position change
        }
    }
    
    fun reorderQueue(fromPosition: Int, toPosition: Int) {
        if (fromPosition >= 0 && fromPosition < trackQueue.size &&
            toPosition >= 0 && toPosition < trackQueue.size) {
            val item = trackQueue.removeAt(fromPosition)
            trackQueue.add(toPosition, item)
            
            // Update current index if needed
            when {
                fromPosition == queueCurrentIndex -> queueCurrentIndex = toPosition
                fromPosition < queueCurrentIndex && toPosition >= queueCurrentIndex -> queueCurrentIndex--
                fromPosition > queueCurrentIndex && toPosition <= queueCurrentIndex -> queueCurrentIndex++
            }
            
            notifyQueueChanged()
            saveProgress() // Save queue changes
        }
    }
    
    fun removeFromQueue(queueIndex: Int) {
        if (queueIndex >= 0 && queueIndex < trackQueue.size && trackQueue.size > 1) {
            trackQueue.removeAt(queueIndex)
            
            // Update current index if needed
            when {
                queueIndex == queueCurrentIndex -> {
                    // If we removed the current track, move to next or previous
                    if (queueCurrentIndex >= trackQueue.size) {
                        queueCurrentIndex = trackQueue.size - 1
                    }
                    currentFileIndex = trackQueue[queueCurrentIndex]
                    val wasPlaying = isPlaying()
                    loadFile(currentFileIndex, 0)
                    if (wasPlaying) {
                        play()
                    }
                }
                queueIndex < queueCurrentIndex -> queueCurrentIndex--
            }
            
            notifyQueueChanged()
            saveProgress() // Save queue changes
        }
    }
    
    fun addToQueue(fileIndex: Int, position: Int = -1) {
        val book = currentBook ?: return
        if (fileIndex >= 0 && fileIndex < book.audioFiles.size) {
            if (position == -1 || position >= trackQueue.size) {
                trackQueue.add(fileIndex)
            } else {
                trackQueue.add(position, fileIndex)
                if (position <= queueCurrentIndex) {
                    queueCurrentIndex++
                }
            }
            notifyQueueChanged()
            saveProgress() // Save queue changes
        }
    }
    
    private fun notifyQueueChanged() {
        playbackStateListener?.onQueueChanged(trackQueue.toList(), queueCurrentIndex)
    }
    
    private fun onFileComplete() {
        if (currentBook == null) return
        
        // Move to next track in queue
        if (queueCurrentIndex < trackQueue.size - 1) {
            queueCurrentIndex++
            currentFileIndex = trackQueue[queueCurrentIndex]
            loadFile(currentFileIndex, 0)
            play()
            notifyQueueChanged()
            saveProgress() // Save queue position change
        } else {
            // Queue finished
            pause()
            saveProgress() // Save final state
        }
    }
    
    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressUpdateJob = lifecycleScope.launch {
            while (mediaPlayer?.isPlaying == true) {
                updateProgress()
                saveProgress()
                delay(1000) // Update every second
            }
        }
    }
    
    private fun stopProgressUpdates() {
        progressUpdateJob?.cancel()
        progressUpdateJob = null
    }
    
    private fun updateProgress() {
        mediaPlayer?.let { mp ->
            playbackStateListener?.onProgressChanged(mp.currentPosition, mp.duration)
        }
    }
    
    private fun saveProgress() {
        currentBook?.let { book ->
            val progress = BookProgress(
                bookPath = book.folderPath,
                currentFileIndex = currentFileIndex,
                currentPosition = getCurrentPosition(),
                queueOrder = trackQueue.toList(), // Save current queue order
                queueCurrentIndex = queueCurrentIndex // Save current queue position
            )
            progressStorage.saveProgress(book.folderPath, progress)
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            // Delete existing channel if it exists (to recreate with new settings)
            try {
                notificationManager.deleteNotificationChannel(CHANNEL_ID)
                android.util.Log.d("AudioPlaybackService", "Deleted existing notification channel")
                // Small delay to ensure channel is fully deleted
                Thread.sleep(100)
            } catch (e: Exception) {
                android.util.Log.d("AudioPlaybackService", "No existing channel to delete")
            }
            
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audiobook Playback",
                NotificationManager.IMPORTANCE_DEFAULT // DEFAULT allows lock screen display
            ).apply {
                description = "Shows current audiobook playback"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC // CRITICAL: Show on lock screen
                setSound(null, null) // No sound for media notifications
                setBypassDnd(false)
            }
            
            notificationManager.createNotificationChannel(channel)
            android.util.Log.d("AudioPlaybackService", "Notification channel created: $CHANNEL_ID")
            android.util.Log.d("AudioPlaybackService", "  Importance: ${channel.importance}")
            android.util.Log.d("AudioPlaybackService", "  Lock screen visibility: ${channel.lockscreenVisibility}")
        }
    }
    
    private fun createNotification(): Notification {
        val book = currentBook
        val title = book?.name ?: "No book selected"
        val isPlaying = isPlaying()
        
        val intent = Intent(this, PlayerActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Create nicer media control actions
        val rewindAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_rew,
            "Rewind",
            createActionIntent(ACTION_REWIND)
        ).build()
        
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_pause,
                "Pause",
                createActionIntent(ACTION_PAUSE)
            ).build()
        } else {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play,
                "Play",
                createActionIntent(ACTION_PLAY)
            ).build()
        }
        
        val forwardAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_ff,
            "Forward",
            createActionIntent(ACTION_FORWARD)
        ).build()
        
        val stopAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop",
            createActionIntent(ACTION_STOP)
        ).build()
        
        val currentFile = book?.audioFiles?.getOrNull(currentFileIndex)
        val fileName = currentFile?.name ?: ""
        val fileInfo = if (book != null) {
            "${currentFileIndex + 1}/${book.audioFiles.size}: $fileName"
        } else {
            ""
        }
        
        // Try to get album art for large icon
        var largeIcon: android.graphics.Bitmap? = null
        try {
            // This would need to be loaded from BookCoverFetcher, but for now we'll skip it
            // to avoid blocking the notification creation
        } catch (e: Exception) {
            android.util.Log.w("AudioPlaybackService", "Could not load large icon: ${e.message}")
        }
        
        // Create MediaStyle notification for lock screen support
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2) // Show rewind, play/pause, forward in compact view
            .setShowCancelButton(true)
            .setCancelButtonIntent(createActionIntent(ACTION_STOP))
        
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (fileInfo.isNotEmpty()) fileInfo else if (isPlaying) "Playing" else "Paused")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .addAction(rewindAction)
            .addAction(playPauseAction)
            .addAction(forwardAction)
            .addAction(stopAction)
            .setStyle(mediaStyle)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT) // CRITICAL: Media transport category
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // CRITICAL: Show on lock screen
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // DEFAULT matches channel importance for lock screen
            .setOngoing(false) // Don't make it ongoing - allows dismissal but still shows on lock screen
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setDeleteIntent(createActionIntent(ACTION_STOP))
        
        if (largeIcon != null) {
            notificationBuilder.setLargeIcon(largeIcon)
        }
        
        val notification = notificationBuilder.build()
        
        val actionCount = notification.actions?.size ?: 0
        android.util.Log.d("AudioPlaybackService", "Notification created: title=$title, isPlaying=$isPlaying, actions=$actionCount")
        if (actionCount == 0) {
            android.util.Log.e("AudioPlaybackService", "WARNING: Notification has no actions!")
        }
        return notification
    }
    
    private fun createActionIntent(action: String): PendingIntent {
        val intent = Intent(action).apply {
            setClass(this@AudioPlaybackService, NotificationReceiver::class.java)
        }
        val requestCode = when (action) {
            ACTION_PLAY -> 1
            ACTION_PAUSE -> 2
            ACTION_STOP -> 3
            ACTION_REWIND -> 4
            ACTION_FORWARD -> 5
            else -> 0
        }
        return PendingIntent.getBroadcast(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    private fun updateNotification() {
        try {
            val notification = createNotification()
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)
            android.util.Log.d("AudioPlaybackService", "Notification updated")
        } catch (e: Exception) {
            android.util.Log.e("AudioPlaybackService", "Error updating notification: ${e.message}", e)
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        android.util.Log.d("AudioPlaybackService", "onStartCommand called, action: ${intent?.action}")
        
        // Check notification permission and channel status (Android 13+)
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!notificationManager.areNotificationsEnabled()) {
                android.util.Log.e("AudioPlaybackService", "ERROR: Notifications are disabled in system settings!")
            } else {
                android.util.Log.d("AudioPlaybackService", "Notifications are enabled")
            }
        }
        
        // Check channel status
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (channel != null) {
                android.util.Log.d("AudioPlaybackService", "Channel status:")
                android.util.Log.d("AudioPlaybackService", "  Importance: ${channel.importance}")
                android.util.Log.d("AudioPlaybackService", "  Lock screen visibility: ${channel.lockscreenVisibility}")
                android.util.Log.d("AudioPlaybackService", "  Can show badge: ${channel.canShowBadge()}")
            } else {
                android.util.Log.e("AudioPlaybackService", "ERROR: Notification channel not found!")
            }
        }
        
        // Always show notification as foreground - CRITICAL for lock screen
        val notification = createNotification()
        
        // Log notification details for debugging
        android.util.Log.d("AudioPlaybackService", "=== NOTIFICATION DEBUG ===")
        android.util.Log.d("AudioPlaybackService", "Notification ID: $NOTIFICATION_ID")
        android.util.Log.d("AudioPlaybackService", "Channel ID: $CHANNEL_ID")
        android.util.Log.d("AudioPlaybackService", "Visibility: ${notification.visibility}")
        android.util.Log.d("AudioPlaybackService", "Priority: ${notification.priority}")
        android.util.Log.d("AudioPlaybackService", "Category: ${notification.category}")
        android.util.Log.d("AudioPlaybackService", "Actions count: ${notification.actions?.size ?: 0}")
        
        startForeground(NOTIFICATION_ID, notification)
        android.util.Log.d("AudioPlaybackService", "Foreground service started with notification ID: $NOTIFICATION_ID")
        
        // Also notify separately to ensure it shows
        notificationManager.notify(NOTIFICATION_ID, notification)
        android.util.Log.d("AudioPlaybackService", "Notification also sent via notify()")
        
        when (intent?.action) {
            ACTION_PLAY -> {
                android.util.Log.d("AudioPlaybackService", "Notification: Play action")
                play()
            }
            ACTION_PAUSE -> {
                android.util.Log.d("AudioPlaybackService", "Notification: Pause action")
                pause()
            }
            ACTION_STOP -> {
                android.util.Log.d("AudioPlaybackService", "Notification: Stop action")
                stopPlayback()
            }
            ACTION_REWIND -> {
                android.util.Log.d("AudioPlaybackService", "Notification: Rewind action")
                rewind()
            }
            ACTION_FORWARD -> {
                android.util.Log.d("AudioPlaybackService", "Notification: Forward action")
                forward()
            }
            else -> {
                // No action, just ensure notification is shown
                android.util.Log.d("AudioPlaybackService", "No action, showing notification")
            }
        }
        
        return START_STICKY
    }
    
    private fun stopPlayback() {
        try {
            pause()
            saveProgress()
            releaseMediaPlayer()
            currentBook = null
            currentFileIndex = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        } catch (e: Exception) {
            android.util.Log.e("AudioPlaybackService", "Error stopping playback: ${e.message}", e)
        }
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        android.util.Log.d("AudioPlaybackService", "App removed from recent tasks - stopping playback")
        stopPlayback()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        saveProgress()
        releaseMediaPlayer()
        stopProgressUpdates()
    }
    
    private fun releaseMediaPlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
    }
    
    companion object {
        private const val CHANNEL_ID = "audiobook_playback_channel"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PLAY = "com.audiobookplayer.ACTION_PLAY"
        private const val ACTION_PAUSE = "com.audiobookplayer.ACTION_PAUSE"
        private const val ACTION_STOP = "com.audiobookplayer.ACTION_STOP"
        private const val ACTION_REWIND = "com.audiobookplayer.ACTION_REWIND"
        private const val ACTION_FORWARD = "com.audiobookplayer.ACTION_FORWARD"
    }
}

