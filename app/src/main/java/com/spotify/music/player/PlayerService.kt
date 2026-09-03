package com.spotify.music.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.spotify.music.App

/**
 * Media3 foreground-playback service. Hosts a MediaSession bound to the shared
 * ExoPlayer so the app gets a proper media notification, lock-screen controls
 * and audio-focus handling while playing in the background.
 *
 * The key requirement on Android 8+ is that every `startForegroundService()`
 * start MUST be answered with `Service.startForeground()` within 5 seconds,
 * otherwise the system kills the service (`ForegroundServiceDidNotStartInTime
 * Exception`). Media3 posts the real media notification asynchronously, so we
 * synchronously promote to foreground with a lightweight placeholder here and
 * let Media3's notification provider replace it under the same id.
 */
class PlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Promote ASAP so that even a startService/startForegroundService path
        // is answered well inside the system's 5s window.
        promoteToForeground()
        val player = App.container(this).playerController.exoPlayer
        // default session keeps notification + focus working out of the box
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Satisfy the 5s startForegroundService window FIRST, before Media3's
        // own (asynchronous) notification provider runs. Because the placeholder
        // is built against a cached channel and never touches app containers,
        // it cannot fail here.
        promoteToForeground()
        val code = super.onStartCommand(intent, flags, startId)
        // Re-assert: a repeated start while the service was backgrounded resets
        // the system timer, so call startForeground again to be safe.
        promoteToForeground()
        return code
    }

    /** Always attempt to put the service into foreground; never throws. */
    private fun promoteToForeground() {
        if (Build.VERSION.SDK_INT < 26) return
        val ok = runCatching {
            startForeground(NOTIFICATION_ID, placeholderNotification())
        }.isSuccess
        if (!ok) {
            // Record the real cause so a repeat isn't masked as a generic
            // system timeout next time the user reports a crash.
            runCatching {
                val f = java.io.File(filesDir, "fgs_error.txt")
                f.appendText("${java.util.Date()} startForeground failed:\n" +
                    Thread.currentThread().stackTrace.joinToString("\n") { it.toString() } + "\n\n")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "音乐播放", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
    }

    /**
     * A stable placeholder used only to meet the foreground-service deadline.
     * It is intentionally self-contained (no container/song lookup) so building
     * it can never throw and prevent startForeground from being reached.
     */
    private fun placeholderNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("刺青音乐")
            .setContentText("正在播放")
            .setShowWhen(false)
            .setOngoing(true)
            .build()

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = App.container(this).playerController.exoPlayer
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.runCatching { release() }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        // Media3's DefaultMediaNotificationProvider uses notification id 0, so
        // reusing it here lets the real media notification replace ours.
        private const val NOTIFICATION_ID = 0
        private const val CHANNEL_ID = "playback"
    }
}