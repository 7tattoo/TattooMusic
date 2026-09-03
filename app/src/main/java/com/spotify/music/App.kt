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
        // Re-apply a previously saved kuwo login cookie so the API (and the
        // home feed) uses the account session across app restarts.
        container.settings.kuwoCookie.value?.let { container.api.setAccountCookie(it) }
        installCrashLogger()
        wireCallbacks()
    }

    /**
     * Persists any uncaught exception stacktrace to a file so crashes can be
     * diagnosed without a device/adb. The file lives in the app's external
     * files dir: /storage/emulated/0/Android/data/<pkg>/files/crash.log
     */
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val file = java.io.File(getExternalFilesDir(null), "crash.log")
                file.parentFile?.mkdirs()
                file.appendText(
                    "\n=== crash at ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} ===\n" +
                        "thread=${thread.name} ${thread.id}\n" +
                        android.util.Log.getStackTraceString(throwable) + "\n"
                )
            }
            prev?.uncaughtException(thread, throwable)
        }
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