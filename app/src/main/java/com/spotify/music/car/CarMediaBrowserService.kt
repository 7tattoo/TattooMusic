package com.spotify.music.car

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.net.Uri
import androidx.media.MediaBrowserServiceCompat
import android.support.v4.media.session.MediaSessionCompat
import com.spotify.music.App
import com.spotify.music.data.model.Song
import com.spotify.music.data.model.SongSource
import com.spotify.music.player.LyricStatus
import com.spotify.music.player.PlayerBusy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * uCar 车载媒体浏览器服务。
 *
 * Exposes a browsable content tree to the car launcher (MediaBrowserService)
 * and mirrors playback + scrolling lyrics into a MediaSessionCompat using the
 * dual-channel vivo lyrics protocol. Playback itself is owned by the shared
 * PlayerController (Media3).
 */
class CarMediaBrowserService : MediaBrowserServiceCompat() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var container: com.spotify.music.AppContainer
    private lateinit var mediaSession: MediaSessionCompat

    // Album art for the car now-playing card: cached per song so the 150ms mirror
    // loop never re-decodes. Decoded on an IO thread from the local file's embedded
    // picture and downscaled so the Parcelable stays well under the Binder limit.
    @Volatile
    private var artBitmap: Bitmap? = null
    private var artKey: String? = null

    private val playAction = android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY
    private val pauseAction = android.support.v4.media.session.PlaybackStateCompat.ACTION_PAUSE
    private val playFromMediaAction = android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
    private val nextAction = android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_NEXT
    private val prevAction = android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS

    override fun onCreate() {
        super.onCreate()
        container = App.container(this)

        mediaSession = MediaSessionCompat(this, "TattooMusicCar").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { container.playerController.exoPlayer.play() }
                override fun onPause() { container.playerController.exoPlayer.pause() }
                override fun onStop() { container.playerController.exoPlayer.pause() }
                override fun onSkipToNext() { container.playerController.next() }
                override fun onSkipToPrevious() { container.playerController.previous() }
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    mediaId?.let { container.playerController.playFromMedia(it) }
                }
            })
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
        startMirroring()
    }

    /** Mirrors playback + lyrics into the car session for both lyrics channels. */
    private fun startMirroring() {
        scope.launch {
            while (isActive) {
                val pc = container.playerController
                val enabled = container.settings.carLyricsEnabled.value

                val state = if (pc.isPlaying.value) {
                    android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
                } else {
                    android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
                }
                val actions = playAction or pauseAction or playFromMediaAction or nextAction or prevAction
                val pb = android.support.v4.media.session.PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(state, pc.positionMs.value, if (pc.isPlaying.value) 1f else 0f)
                    .build()
                mediaSession.setPlaybackState(pb)

                val song = pc.currentSong.value
                val artKeyNow = song?.let { if (it.source == SongSource.LOCAL) "local:${it.localPath}" else null }
                if (artKeyNow != null && artKeyNow != artKey) {
                    artKey = artKeyNow
                    artBitmap = null
                    scope.launch { artBitmap = withContext(Dispatchers.IO) { loadEmbeddedArt(song) } }
                }
                CarLyricsDelegate.update(
                    session = mediaSession,
                    title = song?.title,
                    artist = song?.artist,
                    currentLine = pc.currentLyricText.value,
                    wholeLrc = pc.wholeLrc,
                    isLoading = pc.lyricStatus.value == LyricStatus.LOADING,
                    enabled = enabled,
                    artBitmap = artBitmap,
                    artUri = song?.pic
                )
                delay(150)
            }
        }
    }

    /** Extract + downscale the current song's embedded art for the car card. */
    private fun loadEmbeddedArt(song: Song): Bitmap? {
        if (song.source != SongSource.LOCAL) return null
        val path = song.localPath ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val pic = retriever.embeddedPicture ?: return null
            val bmp = BitmapFactory.decodeByteArray(pic, 0, pic.size) ?: return null
            val w = bmp.width; val h = bmp.height
            val largest = maxOf(w, h)
            if (largest <= 512) bmp else {
                val ns = 512f / largest
                Bitmap.createScaledBitmap(bmp, (w * ns).toInt().coerceAtLeast(1), (h * ns).toInt().coerceAtLeast(1), true)
                    .also { if (it !== bmp) bmp.recycle() }
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        // uCar extension: non-null root so the launcher can always browse.
        val extras = Bundle().apply { putBoolean("android.media.browse.SEARCH_SUPPORTED", true) }
        return BrowserRoot("ROOT", extras)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<android.support.v4.media.MediaBrowserCompat.MediaItem>>) {
        result.sendResult(
            when (parentId) {
                "ROOT" -> topLevel()
                "local" -> container.localMusicRepository.songs.value.map { toMediaItem(it) }.toMutableList()
                "mine" -> container.playlistRepository.selfPlaylists.value
                    .flatMap { it.songs }.map { toMediaItem(it) }.toMutableList()
                "recent" -> container.playlistRepository.recent.value.map { toMediaItem(it) }.toMutableList()
                else -> container.playlistRepository.recent.value.map { toMediaItem(it) }.toMutableList()
            }
        )
    }

    private fun topLevel(): MutableList<android.support.v4.media.MediaBrowserCompat.MediaItem> {
        return mutableListOf(
            browsableItem("local", "本地音乐"),
            browsableItem("mine", "我的歌单"),
            browsableItem("recent", "最近播放")
        )
    }

    private fun browsableItem(id: String, title: String): android.support.v4.media.MediaBrowserCompat.MediaItem {
        val desc = android.support.v4.media.MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .build()
        return android.support.v4.media.MediaBrowserCompat.MediaItem(desc, android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    private fun toMediaItem(song: Song): android.support.v4.media.MediaBrowserCompat.MediaItem {
        val mediaId = if (song.source == SongSource.LOCAL) "local:${song.localPath}" else "online:${song.id}"
        val desc = android.support.v4.media.MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(song.title)
            .setSubtitle(song.artist)
            .setIconUri(song.pic?.let { Uri.parse(it) })
            .build()
        return android.support.v4.media.MediaBrowserCompat.MediaItem(desc, android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    override fun onDestroy() {
        scope.cancel()
        mediaSession.release()
        super.onDestroy()
    }
}