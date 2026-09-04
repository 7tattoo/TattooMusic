package com.spotify.music.player

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

    // ---- playback position/state persistence (resume-on-start) ----
    private val resumePrefs: SharedPreferences =
        context.getSharedPreferences("tattoo_settings", Context.MODE_PRIVATE)
    private val resumeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var lastResumePersistAt = 0L
    private class ResumeSnapshot(val song: Song, val positionMs: Long, val isPlaying: Boolean)

    // callbacks to persist history etc.
    var onSongStarted: ((Song) -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            // 播放/暂停瞬间立即落盘，保证被终止时记住最终状态
            persistResume(force = true)
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
                _positionMs.value = exoPlayer.currentPosition.coerceAtLeast(0)
                _error.value = null
                onSongStarted?.invoke(song)
                loadLyrics(song)
                persistResume(force = true)   // 切歌立即记住新曲目
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
        // Reset the timeline immediately so the UI shows the NEW track's progress
        // from 0 even while it is still resolving/failed, instead of freezing on
        // the previous track's position (fixes the "卡住时进度条还是上一首" bug).
        _positionMs.value = positionMs
        if (song.durationMs > 0) _durationMs.value = song.durationMs
        scope.launch {
            runCatching {
                val mediaItem = withTimeoutOrNull(15_000) { resolveMediaItem(song) }
                if (mediaItem == null) {
                    _events.tryEmit("暂时无法获取该歌曲的播放地址")
                    _busy.value = PlayerBusy.ERROR
                    _error.value = if (song.source == SongSource.LOCAL) "无法读取本地文件" else "获取播放地址失败"
                    // lyrics: still do it so the UI is not stuck in LOADING forever
                    loadLyrics(song)
                    return@launch
                }
                _durationMs.value = if (song.durationMs > 0) song.durationMs else exoPlayer.duration
                exoPlayer.setMediaItem(mediaItem, positionMs)
                exoPlayer.prepare()
                exoPlayer.play()
                // Lyrics can load after audio is already playing; this avoids "lyric load timeout"
                // taking down the entire playback on first app launch when disk/network is slow.
                loadLyrics(song)
            }.onFailure { e ->
                _busy.value = PlayerBusy.ERROR
                _error.value = e.localizedMessage ?: "播放失败"
                loadLyrics(song)
            }
        }
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val clipped = startIndex.coerceIn(0, songs.lastIndex)
        startForegroundPlayback()
        _currentSong.value = songs[clipped]
        _positionMs.value = 0L
        if (songs[clipped].durationMs > 0) _durationMs.value = songs[clipped].durationMs
        loadLyrics(songs[clipped])
        playbackBegin(songs.size)
        scope.launch {
            runCatching {
                // Ordered scan starting at the requested song, wrapping around:
                // first resolvable track starts playback immediately, the rest are
                // resolved in the background and appended as they become ready. This
                // removes the old behaviour of resolving EVERY song up front (which
                // made a big queue take many seconds to begin).
                val n = songs.size
                val order = (0 until n).map { (clipped + it) % n }
                var started = false
                for (idx in order) {
                    val song = songs[idx]
                    val item = withTimeoutOrNull(15_000) { resolveMediaItem(song) }
                    if (item == null) continue
                    if (!started) {
                        _currentSong.value = (item.localConfiguration?.tag as? Song) ?: song
                        exoPlayer.setMediaItem(item)
                        exoPlayer.prepare()
                        exoPlayer.play()
                        started = true
                    } else {
                        exoPlayer.addMediaItem(item)
                    }
                }
                if (!started) {
                    _busy.value = PlayerBusy.ERROR
                    _error.value = "无法加载音频文件"
                }
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
        clearResume()   // 手动停止：不留下可恢复的旧记忆
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

    // ------------- playback position/state memory -------------

    /** Restore the last song, position and play state on app start (resume). */
    fun restoreLastPlayback() {
        val snapshot = loadResume() ?: return
        val song = snapshot.song
        scope.launch {
            startForegroundPlayback()
            playbackBegin(1)
            _currentSong.value = song
            _positionMs.value = snapshot.positionMs
            if (song.durationMs > 0) _durationMs.value = song.durationMs
            _error.value = null
            onSongStarted?.invoke(song)
            loadLyrics(song)
            val mediaItem = withTimeoutOrNull(15_000) { resolveMediaItem(song) }
            if (mediaItem == null) {
                _busy.value = PlayerBusy.ERROR
                _error.value = if (song.source == SongSource.LOCAL) "无法读取本地文件" else "获取播放地址失败"
                return@launch
            }
            _durationMs.value = if (song.durationMs > 0) song.durationMs else exoPlayer.duration
            exoPlayer.setMediaItem(mediaItem, snapshot.positionMs)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = snapshot.isPlaying   // 按记忆恢复 播放/暂停
        }
    }

    /**
     * Persist the current snapshot. Forced on song change / play-pause toggle;
     * otherwise throttled inside the position ticker (low-frequency writes to
     * SharedPreferences are cheap).
     */
    private fun persistResume(force: Boolean) {
        val song = _currentSong.value
        if (song == null) { clearResume(); return }
        val now = System.currentTimeMillis()
        if (!force && now - lastResumePersistAt < RESUME_PERSIST_INTERVAL_MS) return
        lastResumePersistAt = now
        runCatching {
            resumePrefs.edit()
                .putString(RESUME_KEY_SONG, resumeJson.encodeToString(song))
                .putLong(RESUME_KEY_POS, _positionMs.value.coerceAtLeast(0))
                .putBoolean(RESUME_KEY_PLAYING, _isPlaying.value)
                .putLong(RESUME_KEY_AT, now)
                .apply()
        }
    }

    private fun loadResume(): ResumeSnapshot? {
        val songJson = resumePrefs.getString(RESUME_KEY_SONG, null) ?: return null
        val song = runCatching { resumeJson.decodeFromString<Song>(songJson) }.getOrNull() ?: return null
        return ResumeSnapshot(song, resumePrefs.getLong(RESUME_KEY_POS, 0L), resumePrefs.getBoolean(RESUME_KEY_PLAYING, true))
    }

    private fun clearResume() {
        runCatching {
            resumePrefs.edit()
                .remove(RESUME_KEY_SONG).remove(RESUME_KEY_POS)
                .remove(RESUME_KEY_PLAYING).remove(RESUME_KEY_AT)
                .apply()
        }
    }

    // ---------------- helpers ----------------

    private fun playbackBegin(queueSize: Int) {
        lyricJob?.cancel()
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                _positionMs.value = exoPlayer.currentPosition
                _durationMs.value = if (exoPlayer.duration > 0) exoPlayer.duration else _durationMs.value
                persistResume(force = false)   // 按节流周期更新播放进度记忆
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
                    val url = api.getPlayUrl(song.id, expectedDurationMs = song.durationMs)
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
            // UI never gets stuck showing "正在显示歌词" at cold start. runCatching
            // also swallows non-timeout exceptions (e.g. a first-launch IO/read error),
            // which otherwise would leave LyricStatus.LOADING forever.
            val lines = runCatching {
                withTimeoutOrNull(6000) { lyricsRepository.load(song) }
            }.getOrNull().orEmpty()
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

    private companion object {
        private const val RESUME_KEY_SONG = "resume_song"
        private const val RESUME_KEY_POS = "resume_pos"
        private const val RESUME_KEY_PLAYING = "resume_playing"
        private const val RESUME_KEY_AT = "resume_at"
        private const val RESUME_PERSIST_INTERVAL_MS = 3_000L
    }
}