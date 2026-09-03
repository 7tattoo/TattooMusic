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
 */
class PlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = App.container(this).playerController.exoPlayer
        // default session keeps notification + focus working out of the box
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = super.onStartCommand(intent, flags, startId)
        // Media3 posts the real media notification asynchronously. To satisfy the
        // strict 5s window imposed by startForegroundService() we promote the
        // service to foreground immediately with a lightweight placeholder
        // notification (same id, so Media3 replaces it with the full media
        // notification as soon as it is ready). This fixes the
        // ForegroundServiceDidNotStartInTimeException crash on playback.
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching { startForeground(NOTIFICATION_ID, buildForegroundNotification()) }
        }
        return code
    }

    private fun buildForegroundNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "音乐播放", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
        val song = App.container(this).playerController.currentSong.value
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("刺青音乐")
            .setContentText(song?.let { "${it.title} - ${it.artist}" } ?: "正在播放")
            .setShowWhen(false)
            .setOngoing(true)
            .build()
    }

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