package com.d4viddf.hyperbridge.renderer

import android.app.PendingIntent
import android.graphics.Bitmap

sealed class IslandContent {
    data class NormalText(
        val title: String,
        val text: String,
        val icon: Bitmap? = null
    ) : IslandContent()

    data class Progress(
        val title: String,
        val progress: Int,
        val max: Int,
        val isIndeterminate: Boolean
    ) : IslandContent()

    data class Media(
        val title: String,
        val artist: String,
        val isPlaying: Boolean,
        val onPlayPauseIntent: PendingIntent? = null
    ) : IslandContent()
}

data class IslandNotificationData(
    val key: String,
    val packageName: String,
    val content: IslandContent,
    val contentIntent: PendingIntent? = null,
    val timestamp: Long = System.currentTimeMillis()
)
