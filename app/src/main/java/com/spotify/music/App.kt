package com.spotify.music

import android.app.Application
import android.content.Context

/** Simple manual DI container so service and UI share one set of objects. */
class AppContainer(context: Context) {
    val settings = com.spotify.music.data.AppSettings(context)
    val api = com.spotify.music.data.api.KuwoApi()
    val embeddedLyricsReader = com.spotify.music.data.local.EmbeddedLyricsReader()
    val lyricsRepository = com.spotify.music.data.repo.LyricsRepository(api, embeddedLyricsReader)
    val playerController = com.spotify.music.player.PlayerController(context, api, lyricsRepository)
    val homeRepository = com.spotify.music.data.repo.HomeRepository(api)
    val localMusicRepository = com.spotify.music.data.repo.LocalMusicRepository(context, settings)
    val playlistRepository = com.spotify.music.data.repo.PlaylistRepository(context)
    val usbController = com.spotify.music.usb.UsbAudioController(context, settings)
    val sleepTimer = com.spotify.music.service.SleepTimer()
}

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        wireCallbacks()
    }

    private fun wireCallbacks() {
        val c = container
        // record recent plays automatically
        c.playerController.onSongStarted = { song -> c.playlistRepository.addRecent(song) }
        // sleep timer -> pause playback
        c.sleepTimer.onExpire = { c.playerController.exoPlayer.pause() }
        // USB DAC unplugged while exclusive -> auto pause
        c.usbController.onAutoPause = { c.playerController.togglePlayPause() }
    }

    companion object {
        fun of(context: Context): App = context.applicationContext as App
        fun container(context: Context): AppContainer = of(context).container
    }
}