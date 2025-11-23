package com.audiobookplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Rect

object CoverGenerator {
    
    fun generateCover(bookTitle: String, width: Int = 500, height: Int = 500): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Generate gradient background based on book title hash
        val colors = generateColorsFromTitle(bookTitle)
        val gradient = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            colors.first, colors.second,
            Shader.TileMode.CLAMP
        )
        
        val paint = Paint().apply {
            shader = gradient
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        
        // Draw book title or initial
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = if (bookTitle.length <= 20) {
                (width * 0.12f).coerceAtMost(60f)
            } else {
                (width * 0.08f).coerceAtMost(40f)
            }
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        
        // Calculate text position
        val textBounds = Rect()
        val displayText = if (bookTitle.length <= 25) {
            bookTitle
        } else {
            bookTitle.take(22) + "..."
        }
        textPaint.getTextBounds(displayText, 0, displayText.length, textBounds)
        
        val x = width / 2f
        val y = height / 2f + textBounds.height() / 2f
        
        // Add text shadow for better visibility
        val shadowPaint = Paint(textPaint).apply {
            color = Color.argb(100, 0, 0, 0)
            maskFilter = android.graphics.BlurMaskFilter(8f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawText(displayText, x, y, shadowPaint)
        canvas.drawText(displayText, x, y, textPaint)
        
        return bitmap
    }
    
    private fun generateColorsFromTitle(title: String): Pair<Int, Int> {
        // Generate consistent colors based on title hash
        val hash = title.hashCode()
        
        val colorPalettes = listOf(
            Pair(Color.parseColor("#667eea"), Color.parseColor("#764ba2")), // Purple
            Pair(Color.parseColor("#f093fb"), Color.parseColor("#f5576c")), // Pink
            Pair(Color.parseColor("#4facfe"), Color.parseColor("#00f2fe")), // Blue
            Pair(Color.parseColor("#43e97b"), Color.parseColor("#38f9d7")), // Green
            Pair(Color.parseColor("#fa709a"), Color.parseColor("#fee140")), // Orange-Pink
            Pair(Color.parseColor("#30cfd0"), Color.parseColor("#330867")), // Teal-Purple
            Pair(Color.parseColor("#a8edea"), Color.parseColor("#fed6e3")), // Light
            Pair(Color.parseColor("#ff9a9e"), Color.parseColor("#fecfef")), // Soft Pink
            Pair(Color.parseColor("#ffecd2"), Color.parseColor("#fcb69f")), // Warm
            Pair(Color.parseColor("#ff8a80"), Color.parseColor("#ea4c89"))  // Red-Pink
        )
        
        val index = Math.abs(hash) % colorPalettes.size
        return colorPalettes[index]
    }
}


