package com.spotify.music.data.repo

import android.content.ContentUris
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.spotify.music.data.AppSettings
import com.spotify.music.data.model.Song
import com.spotify.music.data.model.SongSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Scans local audio. On API 30+ it prefers direct file-system traversal after
 * the user grants "All files access" (MANAGE_EXTERNAL_STORAGE), which reliably
 * finds music even when MediaStore has not indexed it or returns a null DATA
 * path. Results are emitted incrementally so the UI updates in near real-time.
 * Below API 30 it falls back to MediaStore with runtime read permission.
 */
class LocalMusicRepository(
    private val context: Context,
    private val settings: AppSettings
) {
    private val ctx: Context = context.applicationContext

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cacheJson = Json { ignoreUnknownKeys = true }

    /**
     * Songs shown in the UI's "歌曲" list: all scanned songs minus those whose
     * directory is present in [AppSettings.ignoredDirs]. Combines reactively, so
     * toggling a directory in Settings -> 目录过滤 hides/shows its songs
     * immediately (no rescan required).
     */
    val visibleSongs: StateFlow<List<Song>> =
        combine(_songs, settings.ignoredDirs) { all, ignored ->
            if (ignored.isEmpty()) {
                all
            } else {
                val ign = ignored.asSequence().filter { it.isNotBlank() }.map { it.trimEnd('/') }.toList()
                all.filter { s ->
                    val p = s.localPath ?: return@filter true
                    ign.none { p.startsWith(it) }
                }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        // Restore the last scan from disk so music is shown right after launch
        // without forcing the user to re-scan every time.
        scope.launch(Dispatchers.IO) { restoreCache() }
    }

    private val cacheFile: File get() = File(ctx.filesDir, "local_songs_cache.json")

    private fun restoreCache() {
        val f = cacheFile
        if (!f.exists()) return
        runCatching {
            val cache = cacheJson.decodeFromString<List<Song>>(f.readText())
                .filter { it.source == SongSource.LOCAL && !it.localPath.isNullOrBlank() }
            if (cache.isNotEmpty()) _songs.value = cache
        }
    }

    private fun persist(list: List<Song>) {
        runCatching { cacheFile.writeText(cacheJson.encodeToString(list)) }
    }

    // Audio-only extensions (no video containers like .mp4/.mkv/.avi),
    // so the scanner never picks up video files as music.
    private val supportedExt = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "amr", "mka")

    /** True when we can read files directly (API 30+ all-files access, or legacy READ permission). */
    fun canScanByFileSystem(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** Audio read permission needed on the current SDK (READ_MEDIA_AUDIO on 33+, else READ_EXTERNAL_STORAGE). */
    fun requiredAudioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    suspend fun scan() = withContext(Dispatchers.IO) {
        _isScanning.value = true
        try {
            val ignored = settings.ignoredDirs.value.map { it.trimEnd('/') }
            val roots = settings.musicRoots.value.map { it.trimEnd('/') }
            val list = ArrayList<Song>()
            if (canScanByFileSystem()) {
                val meta = loadMediaStoreMeta()
                for (dir in scanRoots(roots)) {
                    for (f in dir.walkTopDown()) {
                        if (!f.isFile) continue
                        if (f.extension.lowercase() !in supportedExt) continue
                        val path = f.absolutePath
                        list.add(buildSong(path, meta))
                        if (list.size % 20 == 0) _songs.value = list.toList()
                    }
                }
            } else {
                scanViaMediaStore(list, ignored, roots)
            }
            _songs.value = list
            if (list.isNotEmpty()) persist(list)
        } finally {
            _isScanning.value = false
        }
    }

    /** Directories to walk: configured roots, else the whole external storage. */
    private fun scanRoots(roots: List<String>): List<File> {
        val configured = roots.mapNotNull { File(it).takeIf { d -> d.isDirectory } }
        if (configured.isNotEmpty()) return configured
        return Environment.getExternalStorageDirectory()
            .takeIf { it.isDirectory }?.let { listOf(it) } ?: emptyList()
    }

    /** Map real file path -> media metadata, so file walk can reuse title/art/etc. */
    private fun loadMediaStoreMeta(): Map<String, MediaMeta> {
        val out = HashMap<String, MediaMeta>()
        val projection = arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )
        runCatching {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null
            )?.use { c ->
                val dataC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val titleC = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistC = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumC = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val durC = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val albumIdC = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                while (c.moveToNext()) {
                    val data = c.getString(dataC) ?: continue
                    val artUri = if (albumIdC >= 0) {
                        val aid = c.getLong(albumIdC)
                        ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"), aid
                        ).toString()
                    } else null
                    out[data] = MediaMeta(
                        title = if (titleC >= 0) c.getString(titleC) else null,
                        artist = if (artistC >= 0) c.getString(artistC) else null,
                        album = if (albumC >= 0) c.getString(albumC) else null,
                        durationMs = if (durC >= 0) c.getLong(durC) else 0L,
                        artUri = artUri
                    )
                }
            }
        }
        return out
    }

    private fun buildSong(path: String, meta: Map<String, MediaMeta>): Song {
        val f = File(path)
        val m = meta[path]
        return Song(
            id = "local:$path",
            title = m?.title?.takeIf { it.isNotBlank() } ?: f.nameWithoutExtension,
            artist = m?.artist?.takeIf { it.isNotBlank() } ?: "未知歌手",
            album = m?.album?.takeIf { it.isNotBlank() } ?: f.parentFile?.name,
            pic = m?.artUri?.takeIf { it.isNotBlank() },
            durationMs = m?.durationMs ?: 0L,
            source = SongSource.LOCAL,
            localPath = path
        )
    }

    /** Legacy MediaStore-only scan (used when file-system access is unavailable). */
    private fun scanViaMediaStore(list: ArrayList<Song>, ignored: List<String>, roots: List<String>) {
        val rootEnabled = roots.isNotEmpty()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID
        )
        val sort = "${MediaStore.Audio.Media.DISPLAY_NAME} ASC"
        runCatching {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, sort
            )?.use { cursor ->
                val titleC = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistC = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumC = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val durC = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val dataC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val albumIdC = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                while (cursor.moveToNext()) {
                    val data = cursor.getString(dataC) ?: continue
                    val pathFile = File(data)
                    if (pathFile.extension.lowercase() !in supportedExt) continue
                    if (rootEnabled && !roots.any { data.startsWith(it) }) continue
                    val artUri = if (albumIdC >= 0) {
                        ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"),
                            cursor.getLong(albumIdC)
                        ).toString()
                    } else null
                    list.add(
                        Song(
                            id = "local:$data",
                            title = if (titleC >= 0) cursor.getString(titleC)?.takeIf { it.isNotBlank() } ?: pathFile.nameWithoutExtension else pathFile.nameWithoutExtension,
                            artist = if (artistC >= 0) cursor.getString(artistC)?.takeIf { it.isNotBlank() } ?: "未知歌手" else "未知歌手",
                            album = if (albumC >= 0) cursor.getString(albumC)?.takeIf { it.isNotBlank() } ?: pathFile.parentFile?.name else pathFile.parentFile?.name,
                            pic = artUri,
                            durationMs = if (durC >= 0) cursor.getLong(durC) else 0L,
                            source = SongSource.LOCAL,
                            localPath = data
                        )
                    )
                }
            }
        }
    }
}

/** Lightweight media metadata for a single local file (keyed by absolute path). */
private data class MediaMeta(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val artUri: String?
)