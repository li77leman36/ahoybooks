package com.audiobookplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class QueueAdapter(
    private var queue: MutableList<Int>,
    private var currentIndex: Int,
    private val book: Book?,
    private val onItemClick: (Int) -> Unit,
    private val onItemRemove: (Int) -> Unit,
    private val onItemMove: (Int, Int) -> Unit
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val trackName: TextView = itemView.findViewById(R.id.trackName)
        val trackNumber: TextView = itemView.findViewById(R.id.trackNumber)
        val upButton: ImageButton = itemView.findViewById(R.id.upButton)
        val downButton: ImageButton = itemView.findViewById(R.id.downButton)
        val cardView: MaterialCardView = itemView.findViewById(R.id.queueItemCard)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue, parent, false)
        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val fileIndex = queue[position]
        val audioFile = book?.audioFiles?.getOrNull(fileIndex)
        val fileName = audioFile?.name ?: "Unknown"
        
        holder.trackName.text = fileName
        holder.trackNumber.text = "${position + 1}"
        
        // Highlight current track
        if (position == currentIndex) {
            holder.cardView.setCardBackgroundColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.darker_gray)
            )
            holder.trackName.setTextColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.white)
            )
            holder.trackNumber.setTextColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.white)
            )
        } else {
            holder.cardView.setCardBackgroundColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.black)
            )
            holder.trackName.setTextColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.darker_gray)
            )
            holder.trackNumber.setTextColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.darker_gray)
            )
        }
        
        holder.itemView.setOnClickListener {
            onItemClick(position)
        }
        
        // Enable/disable up/down buttons based on position
        holder.upButton.isEnabled = position > 0
        holder.upButton.alpha = if (position > 0) 1.0f else 0.3f
        holder.upButton.setOnClickListener {
            if (position > 0) {
                moveItem(position, position - 1)
            }
        }
        
        holder.downButton.isEnabled = position < queue.size - 1
        holder.downButton.alpha = if (position < queue.size - 1) 1.0f else 0.3f
        holder.downButton.setOnClickListener {
            if (position < queue.size - 1) {
                moveItem(position, position + 1)
            }
        }
    }

    override fun getItemCount(): Int = queue.size

    fun updateQueue(newQueue: List<Int>, newCurrentIndex: Int) {
        queue.clear()
        queue.addAll(newQueue)
        currentIndex = newCurrentIndex
        notifyDataSetChanged()
    }
    
    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition < 0 || fromPosition >= queue.size || 
            toPosition < 0 || toPosition >= queue.size || fromPosition == toPosition) {
            return
        }
        
        // Move in adapter for smooth UI update
        val item = queue.removeAt(fromPosition)
        queue.add(toPosition, item)
        
        // Update current index if needed
        when {
            fromPosition == currentIndex -> currentIndex = toPosition
            fromPosition < currentIndex && toPosition >= currentIndex -> currentIndex--
            fromPosition > currentIndex && toPosition <= currentIndex -> currentIndex++
        }
        
        notifyItemMoved(fromPosition, toPosition)
        // Notify the service of the move with original positions
        // The service will update its queue to match
        onItemMove(fromPosition, toPosition)
    }
}

