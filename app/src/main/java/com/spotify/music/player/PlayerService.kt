package com.spotify.music.player

import android.content.Intent
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
}