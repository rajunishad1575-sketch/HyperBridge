package com.d4viddf.hyperbridge.renderer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.d4viddf.hyperbridge.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class OverlayIslandService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        const val TAG = "OverlayIslandService"
        private const val CHANNEL_ID = "hyperbridge_overlay_channel"
        private const val NOTIF_ID = 40404
        private const val DEFAULT_TIMEOUT_MS = 6000L

        val currentIslandFlow = MutableStateFlow<IslandNotificationData?>(null)

        fun showNotification(data: IslandNotificationData) {
            currentIslandFlow.value = data
        }

        fun removeNotification(key: String) {
            val cur = currentIslandFlow.value
            if (cur != null && cur.key == key) {
                currentIslandFlow.value = null
            }
        }
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val myViewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = myViewModelStore
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private val serviceJob = kotlinx.coroutines.Job()
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + serviceJob)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        startAsForeground()
        setupOverlayWindow()
        observeIslandData()
    }

    private fun startAsForeground() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HyperBridge Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("HyperBridge Overlay Active")
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun setupOverlayWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val maxCapWidth = (screenWidth * 0.60f).toInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = calculateTopOffset()
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayIslandService)
            setViewTreeViewModelStoreOwner(this@OverlayIslandService)
            setViewTreeSavedStateRegistryOwner(this@OverlayIslandService)

            setContent {
                val islandData by currentIslandFlow.collectAsState()
                IslandOverlayContent(
                    data = islandData,
                    maxWidthPx = maxCapWidth,
                    onDismiss = {
                        currentIslandFlow.value = null
                    }
                )
            }
        }

        overlayView = composeView

        try {
            windowManager?.addView(composeView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay window", e)
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    private fun calculateTopOffset(): Int {
        var topMargin = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val windowMetrics = windowManager?.currentWindowMetrics
                val cutout = windowMetrics?.windowInsets?.displayCutout
                if (cutout != null) {
                    val safeTop = cutout.safeInsetTop
                    val boundingRects: List<Rect> = cutout.boundingRects
                    if (boundingRects.isNotEmpty()) {
                        val cutoutRect = boundingRects[0]
                        topMargin = cutoutRect.top
                    } else if (safeTop > 0) {
                        topMargin = safeTop / 2
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error calculating cutout inset", e)
            }
        }

        // If no cutout detected, position below the status bar band
        if (topMargin <= 0) {
            val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusBarHeight = if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 72
            topMargin = statusBarHeight + 8
        }

        return topMargin
    }

    private fun observeIslandData() {
        serviceScope.launch {
            currentIslandFlow.collect { data ->
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                timeoutRunnable = null

                if (data != null) {
                    val runnable = Runnable {
                        if (currentIslandFlow.value?.key == data.key) {
                            currentIslandFlow.value = null
                        }
                    }
                    timeoutRunnable = runnable
                    handler.postDelayed(runnable, DEFAULT_TIMEOUT_MS)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        myViewModelStore.clear()

        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view", e)
            }
        }
        overlayView = null
    }
}

@Composable
fun IslandOverlayContent(
    data: IslandNotificationData?,
    maxWidthPx: Int,
    onDismiss: () -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val maxWidthDp = with(density) { maxWidthPx.toDp() }

    AnimatedVisibility(
        visible = data != null,
        enter = fadeIn(animationSpec = tween(220)) + scaleIn(initialScale = 0.8f),
        exit = fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.8f)
    ) {
        if (data != null) {
            Surface(
                modifier = Modifier
                    .wrapContentHeight()
                    .wrapContentWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clip(CircleShape)
                    .clickable {
                        try {
                            data.contentIntent?.send()
                        } catch (e: Exception) {
                            Log.e("OverlayIsland", "Error sending content intent", e)
                        }
                        onDismiss()
                    },
                shape = CircleShape,
                color = androidx.compose.ui.graphics.Color.Black,
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Box(
                    modifier = Modifier
                        .wrapContentHeight()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when (val content = data.content) {
                        is IslandContent.NormalText -> {
                            NormalTextPill(content = content, maxWidth = maxWidthDp)
                        }
                        is IslandContent.Progress -> {
                            ProgressPill(content = content, maxWidth = maxWidthDp)
                        }
                        is IslandContent.Media -> {
                            MediaPill(content = content, maxWidth = maxWidthDp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NormalTextPill(content: IslandContent.NormalText, maxWidth: androidx.compose.ui.unit.Dp) {
    Row(
        modifier = Modifier
            .wrapContentHeight()
            .width(androidx.compose.ui.unit.min(260.dp, maxWidth)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = content.title,
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (content.text.isNotEmpty()) {
                Text(
                    text = content.text,
                    color = androidx.compose.ui.graphics.Color.LightGray,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ProgressPill(content: IslandContent.Progress, maxWidth: androidx.compose.ui.unit.Dp) {
    Column(
        modifier = Modifier
            .wrapContentHeight()
            .width(androidx.compose.ui.unit.min(240.dp, maxWidth)),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = content.title,
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!content.isIndeterminate && content.max > 0) {
                val pct = ((content.progress.toFloat() / content.max) * 100).toInt()
                Text(
                    text = "$pct%",
                    color = androidx.compose.ui.graphics.Color.LightGray,
                    fontSize = 11.sp
                )
            }
        }

        if (content.isIndeterminate) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = androidx.compose.ui.graphics.Color(0xFF34C759),
                trackColor = androidx.compose.ui.graphics.Color.DarkGray
            )
        } else {
            val progressFraction = if (content.max > 0) (content.progress.toFloat() / content.max).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = androidx.compose.ui.graphics.Color(0xFF34C759),
                trackColor = androidx.compose.ui.graphics.Color.DarkGray
            )
        }
    }
}

@Composable
fun MediaPill(content: IslandContent.Media, maxWidth: androidx.compose.ui.unit.Dp) {
    Row(
        modifier = Modifier
            .wrapContentHeight()
            .width(androidx.compose.ui.unit.min(280.dp, maxWidth)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = content.title,
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (content.artist.isNotEmpty()) {
                Text(
                    text = content.artist,
                    color = androidx.compose.ui.graphics.Color.LightGray,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        IconButton(
            onClick = {
                try {
                    content.onPlayPauseIntent?.send()
                } catch (e: Exception) {
                    Log.e("OverlayIsland", "Error sending play/pause intent", e)
                }
            },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = if (content.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (content.isPlaying) "Pause" else "Play",
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
