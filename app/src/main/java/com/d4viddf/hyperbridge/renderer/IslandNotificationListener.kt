package com.d4viddf.hyperbridge.renderer

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.session.PlaybackState
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.d4viddf.hyperbridge.util.DeviceUtils

class IslandNotificationListener : NotificationListenerService() {

    companion object {
        const val TAG = "IslandNotifListener"
        const val PREFS_NAME = "hyperbridge_overlay_blocklist"
        const val KEY_BLOCKLIST = "blocked_packages"

        fun getBlocklist(context: Context): Set<String> {
            val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getStringSet(KEY_BLOCKLIST, emptySet()) ?: emptySet()
        }

        fun addToBlocklist(context: Context, packageName: String) {
            val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = prefs.getStringSet(KEY_BLOCKLIST, emptySet())?.toMutableSet() ?: mutableSetOf()
            current.add(packageName)
            prefs.edit().putStringSet(KEY_BLOCKLIST, current).apply()
        }

        fun removeFromBlocklist(context: Context, packageName: String) {
            val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = prefs.getStringSet(KEY_BLOCKLIST, emptySet())?.toMutableSet() ?: mutableSetOf()
            current.remove(packageName)
            prefs.edit().putStringSet(KEY_BLOCKLIST, current).apply()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "IslandNotificationListener connected")
        checkAndStartOverlayService()
    }

    private fun checkAndStartOverlayService() {
        if (!shouldRunOverlay()) return
        if (Settings.canDrawOverlays(this)) {
            val serviceIntent = Intent(this, OverlayIslandService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    private fun shouldRunOverlay(): Boolean {
        // HyperOS devices use the existing HyperOS island path
        // Only non-HyperOS devices run this overlay path
        val isXiaomi = DeviceUtils.isXiaomi
        val isCompatibleOS = DeviceUtils.isCompatibleOS()
        val isHyperOS = isXiaomi && isCompatibleOS
        return !isHyperOS
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!shouldRunOverlay()) return

        // 1. Filter own package and android system noise
        val pkg = sbn.packageName
        if (pkg == packageName || pkg == "android") return

        // 2. Filter SharedPreferences blocklist
        val blocklist = getBlocklist(this)
        if (blocklist.contains(pkg)) return

        // 3. Filter group summary
        val notification = sbn.notification
        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) return

        // 4. Parse into exactly 3 types: Media, Progress, or Normal text
        val islandContent = parseNotificationContent(sbn) ?: return

        checkAndStartOverlayService()

        val data = IslandNotificationData(
            key = sbn.key,
            packageName = pkg,
            content = islandContent,
            contentIntent = notification.contentIntent
        )
        OverlayIslandService.showNotification(data)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!shouldRunOverlay()) return
        OverlayIslandService.removeNotification(sbn.key)
    }

    private fun parseNotificationContent(sbn: StatusBarNotification): IslandContent? {
        val n = sbn.notification
        val extras = n.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.trim()
            ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: ""

        val template = extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
        val isMediaStyle = template.contains("MediaStyle") || n.category == Notification.CATEGORY_TRANSPORT

        // Type 1: Media (title + play/pause)
        if (isMediaStyle) {
            val isPlaying = n.actions?.any { action ->
                val actionTitle = action.title?.toString()?.lowercase() ?: ""
                actionTitle.contains("pause")
            } ?: false

            val playPauseAction = n.actions?.firstOrNull { action ->
                val actionTitle = action.title?.toString()?.lowercase() ?: ""
                actionTitle.contains("pause") || actionTitle.contains("play")
            }

            return IslandContent.Media(
                title = if (title.isNotEmpty()) title else "Media Playing",
                artist = text,
                isPlaying = isPlaying,
                onPlayPauseIntent = playPauseAction?.actionIntent
            )
        }

        // Type 2: Progress bar
        val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val progress = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val isIndeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)

        if (progressMax > 0 || isIndeterminate) {
            val displayTitle = if (title.isNotEmpty()) title else text.ifEmpty { "In progress..." }
            return IslandContent.Progress(
                title = displayTitle,
                progress = progress,
                max = progressMax,
                isIndeterminate = isIndeterminate
            )
        }

        // Type 3: Normal text
        if (title.isNotEmpty() || text.isNotEmpty()) {
            return IslandContent.NormalText(
                title = title.ifEmpty { "Notification" },
                text = text
            )
        }

        return null
    }
}
