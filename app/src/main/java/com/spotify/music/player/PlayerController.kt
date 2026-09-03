package com.spotify.music.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.spotify.music.data.api.KuwoApi
import com.spotify.music.data.model.LyricLine
import com.spotify.music.data.model.Song
import com.spotify.music.data.model.SongSource
import com.spotify.music.data.repo.LyricsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Enum describing playback readiness. */
enum class PlayerBusy { IDLE, LOADING, READY, ERROR }

enum class LyricStatus { NONE, LOADING, READY }

/**
 * Application-wide playback engine built on ExoPlayer / Media3.
 * Exposes lightweight state flows consumed by Compose UI, the MediaSession
 * service and the car-lyrics mirror.
 */
class PlayerController(
    val context: Context,
    private val api: KuwoApi = KuwoApi(),
    private val lyricsRepository: LyricsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        // Some SoC FLAC decoders don't support high-resolution/unsupported track
        // specs; enabling decoder fallback lets ExoPlayer back off to another
        // decoder instead of crashing the app on FLAC playback.
        .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .build()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _busy = MutableStateFlow(PlayerBusy.IDLE)
    val busy: StateFlow<PlayerBusy> = _busy.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _hasNext = MutableStateFlow(false)
    val hasNext: StateFlow<Boolean> = _hasNext.asStateFlow()

    private val _hasPrevious = MutableStateFlow(false)
    val hasPrevious: StateFlow<Boolean> = _hasPrevious.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // lyrics
    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyrics: StateFlow<List<LyricLine>> = _lyrics.asStateFlow()

    private val _lyricStatus = MutableStateFlow(LyricStatus.NONE)
    val lyricStatus: StateFlow<LyricStatus> = _lyricStatus.asStateFlow()

    private val _currentLyricIndex = MutableStateFlow(-1)
    val currentLyricIndex: StateFlow<Int> = _currentLyricIndex.asStateFlow()

    private val _currentLyricText = MutableStateFlow<String?>(null)
    val currentLyricText: StateFlow<String?> = _currentLyricText.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    // car + notification friendly data
    val wholeLrc: String
        get() = lyricsRepository.toLrcText(_lyrics.value)

    private val urlCache = ConcurrentHashMap<String, String>()
    private val mediaRegistry = ConcurrentHashMap<String, Song>()
    private var tickerJob: Job? = null
    private var lyricJob: Job? = null
    private val playbackUserId = AtomicInteger(0)

    // callbacks to persist history etc.
    var onSongStarted: ((Song) -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onPlaybackStateChanged(state: Int) {
            _busy.value = when (state) {
                Player.STATE_BUFFERING -> PlayerBusy.LOADING
                Player.STATE_READY -> PlayerBusy.READY
                Player.STATE_IDLE -> PlayerBusy.IDLE
                Player.STATE_ENDED -> PlayerBusy.READY
                else -> PlayerBusy.IDLE
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _busy.value = PlayerBusy.ERROR
            _error.value = error.localizedMessage ?: "播放出错"
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val song = (mediaItem?.localConfiguration?.tag as? Song)
                ?: mediaItem?.let { mediaRegistry[it.mediaId] }
            if (song != null) {
                _currentSong.value = song
                _error.value = null
                onSongStarted?.invoke(song)
                loadLyrics(song)
            }
            updateNavAvailability()
        }

        override fun onPlaybackParametersChanged(parameters: androidx.media3.common.PlaybackParameters) {
            // no-op
        }
    }

    init {
        exoPlayer.addListener(listener)
    }

    // ---------------- public controls ----------------

    fun playSong(song: Song, positionMs: Long = 0) {
        playbackBegin(1)
        startForegroundPlayback()
        _currentSong.value = song
        loadLyrics(song)
        scope.launch {
            runCatching {
                val mediaItem = resolveMediaItem(song)
                if (mediaItem == null) {
                    _events.tryEmit("暂时无法获取该歌曲的播放地址")
                    _busy.value = PlayerBusy.ERROR
                    _error.value = if (song.source == SongSource.LOCAL) "无法读取本地文件" else "获取播放地址失败"
                    return@launch
                }
                _durationMs.value = if (song.durationMs > 0) song.durationMs else exoPlayer.duration
                exoPlayer.setMediaItem(mediaItem, positionMs)
                exoPlayer.prepare()
                exoPlayer.play()
            }.onFailure { e ->
                _busy.value = PlayerBusy.ERROR
                _error.value = e.localizedMessage ?: "播放失败"
            }
        }
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val clipped = startIndex.coerceIn(0, songs.lastIndex)
        startForegroundPlayback()
        _currentSong.value = songs[clipped]
        loadLyrics(songs[clipped])
        playbackBegin(songs.size)
        scope.launch {
            runCatching {
                val items = ArrayList<MediaItem>()
                for (song in songs) {
                    resolveMediaItem(song)?.let { items.add(it) }
                }
                if (items.isEmpty()) {
                    _busy.value = PlayerBusy.ERROR
                    _error.value = "无法加载音频文件"
                    return@launch
                }
                // locate the requested start song inside the resolved (non-null) items
                var start = clipped.coerceIn(0, items.lastIndex)
                for (i in items.indices) {
                    if (items[i].localConfiguration?.tag == songs[clipped]) {
                        start = i
                        break
                    }
                }
                exoPlayer.setMediaItems(items, start, 0L)
                exoPlayer.prepare()
                exoPlayer.play()
            }.onFailure { e ->
                _busy.value = PlayerBusy.ERROR
                _error.value = e.localizedMessage ?: "播放失败"
            }
        }
    }

    fun togglePlayPause() {
        if (exoPlayer.playWhenReady) {
            exoPlayer.pause()
        } else {
            when (exoPlayer.playbackState) {
                Player.STATE_IDLE, Player.STATE_ENDED -> {
                    val cur = _currentSong.value
                    if (cur != null) {
                        exoPlayer.prepare()
                        exoPlayer.play()
                    }
                }
                else -> exoPlayer.play()
            }
        }
    }

    fun seekTo(ms: Long) {
        val duration = exoPlayer.duration
        val target = if (duration > 0) ms.coerceIn(0, duration) else ms.coerceAtLeast(0)
        exoPlayer.seekTo(target)
        _positionMs.value = target
    }

    fun next() {
        if (exoPlayer.hasNextMediaItem()) exoPlayer.seekToNext() else stopPlayback()
    }

    fun previous() {
        if (exoPlayer.hasPreviousMediaItem()) exoPlayer.seekToPrevious()
        else exoPlayer.seekTo(0)
    }

    fun setRepeatMode(mode: Int) {
        exoPlayer.repeatMode = mode
    }

    val repeatMode: Int get() = exoPlayer.repeatMode

    fun toggleShuffle() {
        exoPlayer.shuffleModeEnabled = !exoPlayer.shuffleModeEnabled
    }

    val shuffle: Boolean get() = exoPlayer.shuffleModeEnabled

    fun playFromMedia(mediaId: String) {
        mediaRegistry[mediaId]?.let(::playSong)
    }

    fun stopPlayback() {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _currentSong.value = null
        _isPlaying.value = false
        _busy.value = PlayerBusy.IDLE
        _lyrics.value = emptyList()
        _lyricStatus.value = LyricStatus.NONE
        _currentLyricIndex.value = -1
        _currentLyricText.value = null
        stopForegroundPlayback()
    }

    private fun startForegroundPlayback() {
        runCatching {
            // Plain startService: the service promotes itself to foreground in
            // onCreate/onStartCommand. This avoids the hard 5-second window imposed
            // by startForegroundService() (ForegroundServiceDidNotStartInTime
            // Exception) when the main thread is briefly busy at playback start.
            context.startService(Intent(context, PlayerService::class.java))
        }
    }

    private fun stopForegroundPlayback() {
        runCatching { context.stopService(Intent(context, PlayerService::class.java)) }
    }

    fun release() {
        playbackEnd()
        exoPlayer.release()
    }

    // ---------------- helpers ----------------

    private fun playbackBegin(queueSize: Int) {
        lyricJob?.cancel()
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                _positionMs.value = exoPlayer.currentPosition
                _durationMs.value = if (exoPlayer.duration > 0) exoPlayer.duration else _durationMs.value
                delay(150)
            }
        }
        updateNavAvailability()
    }

    private fun playbackEnd() {
        tickerJob?.cancel()
        lyricJob?.cancel()
    }

    private fun updateNavAvailability() {
        _hasNext.value = exoPlayer.hasNextMediaItem()
        _hasPrevious.value = exoPlayer.hasPreviousMediaItem() || _positionMs.value > 0
    }

    /** Resolve a MediaItem (stream URL for online, file URI for local). */
    private suspend fun resolveMediaItem(song: Song): MediaItem? {
        val uri = when (song.source) {
            SongSource.LOCAL -> {
                val p = song.localPath
                if (p.isNullOrBlank() || !File(p).isFile) null else Uri.fromFile(File(p))
            }
            SongSource.ONLINE -> {
                val cached = urlCache[song.id]
                if (cached != null) Uri.parse(cached)
                else {
                    val url = api.getPlayUrl(song.id)
                    if (url != null) {
                        urlCache[song.id] = url
                        Uri.parse(url)
                    } else null
                }
            }
        } ?: return null

        val mediaId = if (song.source == SongSource.LOCAL) "local:${song.localPath}" else "online:${song.id}"
        mediaRegistry[mediaId] = song
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            .setTag(song)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .setArtworkUri(
                        // Only use remote artwork in media metadata. Local
                        // content:// albumart can't always be resolved by the
                        // media session / notification thread and may crash the
                        // app; the in-app UI loads album art separately.
                        if (song.source == SongSource.ONLINE)
                            song.pic?.let { Uri.parse(it) }
                        else null
                    )
                    .build()
            )
            .build()
    }

    private fun loadLyrics(song: Song) {
        _lyrics.value = emptyList()
        _currentLyricIndex.value = -1
        lyricJob?.cancel()
        lyricJob = scope.launch {
            _lyricStatus.value = LyricStatus.LOADING
            // Cap lyric resolution (esp. the network fallback for local songs) so the
            // UI never gets stuck showing "正在显示歌词" at cold start.
            val lines = withTimeoutOrNull(6000) { lyricsRepository.load(song) }.orEmpty()
            _lyrics.value = lines
            _lyricStatus.value = if (lines.isEmpty()) LyricStatus.NONE else LyricStatus.READY
            // track current lyric index
            while (isActive) {
                val pos = _positionMs.value
                var idx = -1
                for (i in lines.indices) {
                    if (lines[i].timeMs <= pos) idx = i else break
                }
                if (idx != _currentLyricIndex.value) {
                    _currentLyricIndex.value = idx
                    _currentLyricText.value = if (idx >= 0) lines[idx].text else null
                }
                delay(150)
            }
        }
    }

}