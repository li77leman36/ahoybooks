package com.audiobookplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Button
import android.widget.TextView
import android.widget.EditText
import java.io.File
import android.os.CountDownTimer
import android.text.InputType
import android.view.ContextThemeWrapper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.bottomsheet.BottomSheetDialog

class PlayerActivity : AppCompatActivity() {
    
    private var service: AudioPlaybackService? = null
    private var isBound = false
    private var currentBook: Book? = null
    private lateinit var bookNameStorage: BookNameStorage
    
    private lateinit var bookTitleText: TextView
    private lateinit var fileNameText: TextView
    private lateinit var albumArtImage: android.widget.ImageView
    private lateinit var playPauseButton: android.widget.ImageButton
    private lateinit var rewindButton: android.widget.ImageButton
    private lateinit var forwardButton: android.widget.ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var sleepTimerButton: com.google.android.material.button.MaterialButton
    private lateinit var queueButton: com.google.android.material.button.MaterialButton
    
    private var sleepTimer: CountDownTimer? = null
    private var timerMinutes: Int = 0
    private var queueAdapter: QueueAdapter? = null
    private var queueDialog: BottomSheetDialog? = null
    private var queueRecyclerView: RecyclerView? = null
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            try {
                if (binder == null) {
                    android.util.Log.e("PlayerActivity", "Service binder is null")
                    return
                }
                
                val localBinder = binder as? AudioPlaybackService.LocalBinder
                if (localBinder == null) {
                    android.util.Log.e("PlayerActivity", "Binder is not AudioPlaybackService.LocalBinder")
                    return
                }
                
                this@PlayerActivity.service = localBinder.getService()
                isBound = true
                
                android.util.Log.d("PlayerActivity", "Service connected, loading book...")
                
                this@PlayerActivity.service?.setPlaybackStateListener(playbackStateListener)
                
                currentBook?.let { book ->
                    android.util.Log.d("PlayerActivity", "Loading book in service: ${book.name}")
                    try {
                        this@PlayerActivity.service?.loadBook(book, resumeFromProgress = true)
                        updateUI()
                        // Queue will be updated via onQueueChanged callback
                    } catch (e: Exception) {
                        android.util.Log.e("PlayerActivity", "Error loading book in service: ${e.message}", e)
                        runOnUiThread {
                            val lightContext = ContextThemeWrapper(this@PlayerActivity, android.R.style.Theme_Material_Light_Dialog)
                            MaterialAlertDialogBuilder(lightContext)
                                .setTitle("Playback Error")
                                .setMessage("Could not start playback: ${e.message}")
                                .setPositiveButton("OK") { _, _ -> }
                                .show()
                        }
                    }
                } ?: run {
                    android.util.Log.e("PlayerActivity", "Current book is null when service connected")
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "Error in onServiceConnected: ${e.message}", e)
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            android.util.Log.w("PlayerActivity", "Service disconnected")
            isBound = false
            service = null
        }
    }
    
    private val playbackStateListener = object : AudioPlaybackService.PlaybackStateListener {
        override fun onProgressChanged(position: Int, duration: Int) {
            try {
                runOnUiThread {
                    try {
                        if (::seekBar.isInitialized && !seekBar.isPressed) {
                            if (duration > 0) {
                                seekBar.max = duration
                            }
                            if (position >= 0) {
                                seekBar.progress = position
                            }
                        }
                        if (::positionText.isInitialized) {
                            positionText.text = formatTime(position)
                        }
                        if (::durationText.isInitialized) {
                            durationText.text = formatTime(duration)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("PlayerActivity", "Error updating progress UI: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "Error in onProgressChanged: ${e.message}")
            }
        }
        
        override fun onFileChanged(fileIndex: Int, fileName: String) {
            try {
                runOnUiThread {
                    try {
                        currentBook?.let { book ->
                            if (::fileNameText.isInitialized) {
                                fileNameText.text = "${fileIndex + 1}/${book.audioFiles.size}: $fileName"
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("PlayerActivity", "Error updating file name UI: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "Error in onFileChanged: ${e.message}")
            }
        }
        
        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            try {
                runOnUiThread {
                    try {
                        if (::playPauseButton.isInitialized) {
                            val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                            playPauseButton.setImageResource(iconRes)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("PlayerActivity", "Error updating play button: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "Error in onPlaybackStateChanged: ${e.message}")
            }
        }
        
        override fun onPlaybackError(message: String) {
            try {
                runOnUiThread {
                    val lightContext = ContextThemeWrapper(this@PlayerActivity, android.R.style.Theme_Material_Light_Dialog)
                    MaterialAlertDialogBuilder(lightContext)
                        .setTitle("Playback Error")
                        .setMessage(message)
                        .setPositiveButton("OK") { _, _ -> }
                        .show()
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "Error showing playback error dialog: ${e.message}")
            }
        }
        
        override fun onQueueChanged(queue: List<Int>, currentIndex: Int) {
            try {
                runOnUiThread {
                    queueAdapter?.updateQueue(queue, currentIndex)
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "Error updating queue UI: ${e.message}")
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        
        bookNameStorage = BookNameStorage(this)
        
        try {
            val bookPath = intent.getStringExtra("book_path")
            
            if (bookPath == null) {
                android.util.Log.e("PlayerActivity", "No book path provided")
                showErrorAndFinish("No book path provided")
                return
            }
            
            android.util.Log.d("PlayerActivity", "Loading book from path: $bookPath")
            
            // Load book - check if it's a URI or a file path
            val bookScanner = BookScanner(this)
            currentBook = if (bookPath.startsWith("content://")) {
                // It's a URI - use DocumentFile scanning
                android.util.Log.d("PlayerActivity", "Loading book from URI: $bookPath")
                try {
                    val uri = Uri.parse(bookPath)
                    val books = bookScanner.scanForBooksWithUri(this, uri)
                    books.firstOrNull() // Get the first book (or null if none found)
                } catch (e: Exception) {
                    android.util.Log.e("PlayerActivity", "Error loading book from URI: ${e.message}", e)
                    null
                }
            } else {
                // It's a file path - use regular scanning
                android.util.Log.d("PlayerActivity", "Loading book from path: $bookPath")
                bookScanner.scanCustomFolder(bookPath)
            }
            
            if (currentBook == null) {
                android.util.Log.e("PlayerActivity", "Could not load book from: $bookPath")
                showErrorAndFinish("Could not load audiobook from:\n$bookPath\n\nMake sure the folder contains audio files.")
                return
            }
            
            android.util.Log.d("PlayerActivity", "Book loaded: ${currentBook?.name}, ${currentBook?.audioFiles?.size ?: 0} files")
            
            if (currentBook?.audioFiles.isNullOrEmpty()) {
                android.util.Log.e("PlayerActivity", "Book has no audio files")
                showErrorAndFinish("No audio files found in this book.")
                return
            }
            
            setupUI()
            bindService()
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivity", "Error in onCreate: ${e.message}", e)
            showErrorAndFinish("Error loading player: ${e.message}")
        }
    }
    
    private fun showErrorAndFinish(message: String) {
        val lightContext = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog)
        MaterialAlertDialogBuilder(lightContext)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> finish() }
            .setOnDismissListener { finish() }
            .show()
    }
    
    private fun setupUI() {
        bookTitleText = findViewById(R.id.bookTitle)
        fileNameText = findViewById(R.id.fileName)
        albumArtImage = findViewById(R.id.albumArt)
        playPauseButton = findViewById(R.id.playPauseButton)
        rewindButton = findViewById(R.id.rewindButton)
        forwardButton = findViewById(R.id.forwardButton)
        seekBar = findViewById(R.id.seekBar)
        positionText = findViewById(R.id.positionText)
        durationText = findViewById(R.id.durationText)
        sleepTimerButton = findViewById(R.id.sleepTimerButton)
        queueButton = findViewById(R.id.queueButton)

        currentBook?.let { book ->
            val displayName = bookNameStorage.getDisplayName(book.folderPath, book.name)
            bookTitleText.text = displayName
            
            // Make title clickable for renaming
            bookTitleText.setOnClickListener {
                showRenameDialog(book.folderPath, displayName)
            }
            
            // Load book cover (embedded art or online search)
            lifecycleScope.launch {
                val cover = BookCoverFetcher.getBookCover(this@PlayerActivity, book)
                if (cover != null) {
                    albumArtImage.setImageBitmap(cover)
                    albumArtImage.background = null
                } else {
                    albumArtImage.setImageResource(android.R.color.transparent)
                    albumArtImage.setBackgroundResource(R.drawable.default_cover)
                }
            }
        }
        
        playPauseButton.setOnClickListener {
            try {
                service?.let { s ->
                    if (s.isPlaying()) {
                        s.pause()
                    } else {
                        s.play()
                    }
                } ?: run {
                    android.util.Log.w("PlayerActivity", "Service is null when play/pause clicked")
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "Error in play/pause: ${e.message}", e)
            }
        }
        
        rewindButton.setOnClickListener {
            try {
                service?.rewind() ?: run {
                    android.util.Log.w("PlayerActivity", "Service is null when rewind clicked")
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "Error in rewind: ${e.message}", e)
            }
        }
        
        forwardButton.setOnClickListener {
            try {
                service?.forward() ?: run {
                    android.util.Log.w("PlayerActivity", "Service is null when forward clicked")
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "Error in forward: ${e.message}", e)
            }
        }
        
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    positionText.text = formatTime(progress)
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    service?.seekTo(it.progress)
                }
            }
        })
        
        sleepTimerButton.setOnClickListener {
            showSleepTimerDialog()
        }
        
        queueButton.setOnClickListener {
            showQueueDialog()
        }
        
        updateSleepTimerButton()
        setupQueue()
    }
    
    private fun setupQueue() {
        currentBook?.let { book ->
            queueAdapter = QueueAdapter(
                queue = mutableListOf(),
                currentIndex = 0,
                book = book,
                onItemClick = { queueIndex ->
                    service?.moveToQueuePosition(queueIndex)
                    queueDialog?.dismiss()
                },
                onItemRemove = { queueIndex ->
                    service?.removeFromQueue(queueIndex)
                },
                onItemMove = { from, to ->
                    service?.reorderQueue(from, to)
                }
            )
        }
        
        // Initialize queue from service if available (service might already be connected)
        updateQueueFromService()
    }
    
    private fun showQueueDialog() {
        val dialog = BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_queue, null)
        dialog.setContentView(dialogView)
        
        queueRecyclerView = dialogView.findViewById(R.id.queueRecyclerView)
        queueRecyclerView?.layoutManager = LinearLayoutManager(this)
        queueRecyclerView?.adapter = queueAdapter
        
        // Enable drag to reorder - allows moving multiple positions at once
        queueRecyclerView?.let { recyclerView ->
            val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                0
            ) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    val from = viewHolder.absoluteAdapterPosition
                    val to = target.absoluteAdapterPosition
                    if (from != RecyclerView.NO_POSITION && to != RecyclerView.NO_POSITION && from != to) {
                        // Move in adapter first for smooth UI update
                        queueAdapter?.moveItem(from, to)
                    }
                    return true
                }
                
                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    // Not used, but required by interface
                }
                
                override fun isLongPressDragEnabled(): Boolean {
                    return true
                }
            })
            itemTouchHelper.attachToRecyclerView(recyclerView)
        }
        
        queueDialog = dialog
        dialog.show()
        
        // Update queue when dialog is shown
        updateQueueFromService()
    }
    
    private fun updateQueueFromService() {
        service?.let { s ->
            val queue = s.getQueue()
            val currentIndex = s.getQueueCurrentIndex()
            if (queue.isNotEmpty()) {
                queueAdapter?.updateQueue(queue, currentIndex)
            }
        }
    }
    
    private fun showSleepTimerDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = getString(R.string.enter_minutes)
        input.setTextColor(android.graphics.Color.BLACK)
        input.setHintTextColor(android.graphics.Color.GRAY)
        
        if (timerMinutes > 0) {
            input.setText(timerMinutes.toString())
        }
        
        val lightContext = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog)
        MaterialAlertDialogBuilder(lightContext)
            .setTitle(getString(R.string.sleep_timer))
            .setView(input)
            .setPositiveButton(getString(R.string.set_timer)) { _, _ ->
                val minutes = input.text.toString().toIntOrNull() ?: 0
                if (minutes > 0 && minutes <= 480) {
                    setSleepTimer(minutes)
                } else {
                    MaterialAlertDialogBuilder(lightContext)
                        .setTitle("Invalid Time")
                        .setMessage("Please enter a number between 1 and 480 minutes.")
                        .setPositiveButton("OK") { _, _ -> }
                        .show()
                }
            }
            .setNegativeButton(if (timerMinutes > 0) getString(R.string.timer_off) else "Cancel") { _, _ ->
                if (timerMinutes > 0) {
                    cancelSleepTimer()
                }
            }
            .show()
    }
    
    private fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        timerMinutes = minutes
        val totalMillis = minutes * 60 * 1000L
        
        sleepTimer = object : CountDownTimer(totalMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val remainingMinutes = (millisUntilFinished / 60000).toInt()
                val remainingSeconds = ((millisUntilFinished % 60000) / 1000).toInt()
                runOnUiThread {
                    sleepTimerButton.text = getString(R.string.timer_remaining, remainingMinutes, remainingSeconds)
                }
            }
            
            override fun onFinish() {
                runOnUiThread {
                    sleepTimerButton.text = getString(R.string.sleep_timer)
                    timerMinutes = 0
                    
                    // Stop playback
                    service?.pause()
                    
                    val lightContext = ContextThemeWrapper(this@PlayerActivity, android.R.style.Theme_Material_Light_Dialog)
                    MaterialAlertDialogBuilder(lightContext)
                        .setTitle(getString(R.string.sleep_timer))
                        .setMessage(getString(R.string.timer_expired))
                        .setPositiveButton("OK") { _, _ -> }
                        .show()
                }
            }
        }
        
        sleepTimer?.start()
        updateSleepTimerButton()
        
        android.widget.Toast.makeText(
            this,
            getString(R.string.timer_set, minutes),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
    
    private fun cancelSleepTimer() {
        sleepTimer?.cancel()
        sleepTimer = null
        timerMinutes = 0
        updateSleepTimerButton()
    }
    
    private fun updateSleepTimerButton() {
        if (timerMinutes > 0) {
            val remainingMinutes = timerMinutes
            sleepTimerButton.text = getString(R.string.timer_minutes, remainingMinutes)
        } else {
            sleepTimerButton.text = getString(R.string.sleep_timer)
        }
    }
    
    private fun bindService() {
        try {
            val intent = Intent(this, AudioPlaybackService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            val bound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            android.util.Log.d("PlayerActivity", "bindService called, result: $bound")
            if (!bound) {
                android.util.Log.e("PlayerActivity", "Failed to bind to service")
            }
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivity", "Error binding service: ${e.message}", e)
            runOnUiThread {
                val lightContext = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog)
                MaterialAlertDialogBuilder(lightContext)
                    .setTitle("Service Error")
                    .setMessage("Could not start playback service: ${e.message}")
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .show()
            }
        }
    }
    
    private fun updateUI() {
        service?.let { s ->
            val iconRes = if (s.isPlaying()) R.drawable.ic_pause else R.drawable.ic_play
            playPauseButton.setImageResource(iconRes)
            
            val fileIndex = s.getCurrentFileIndex()
            currentBook?.let { book ->
                val audioFile = book.audioFiles.getOrNull(fileIndex)
                if (audioFile != null) {
                    fileNameText.text = "${fileIndex + 1}/${book.audioFiles.size}: ${audioFile.name}"
                }
            }
        }
    }
    
    private fun formatTime(milliseconds: Int): String {
        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }
    
    private fun showRenameDialog(bookPath: String, currentName: String) {
        val input = EditText(this).apply {
            setText(currentName)
            selectAll()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        
        val lightContext = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog)
        MaterialAlertDialogBuilder(lightContext)
            .setTitle("Rename Book")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != currentName) {
                    bookNameStorage.saveBookName(bookPath, newName)
                    bookTitleText.text = newName
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset") { _, _ ->
                bookNameStorage.clearBookName(bookPath)
                currentBook?.let { book ->
                    bookTitleText.text = book.name
                }
            }
            .show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cancelSleepTimer()
        if (isBound) {
            service?.setPlaybackStateListener(null)
            unbindService(serviceConnection)
            isBound = false
        }
    }
}

