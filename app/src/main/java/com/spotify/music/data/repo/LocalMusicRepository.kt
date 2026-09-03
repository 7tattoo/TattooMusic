package com.spotify.music.data.repo

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.spotify.music.data.AppSettings
import com.spotify.music.data.model.Song
import com.spotify.music.data.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Scans the device media store for local audio files, honoring the configured
 * supported extensions and the directory filter. Also resolves sidecar/embedded lyrics.
 */
class LocalMusicRepository(
    private val context: Context,
    private val settings: AppSettings
) {
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val supportedExt = setOf("mp3", "m4a", "mp4", "aac", "flac", "ogg", "oga", "wav", "opus", "wma", "amr")

    suspend fun scan() = withContext(Dispatchers.IO) {
        _isScanning.value = true
        try {
            val ignored = settings.ignoredDirs.value.map { it.trimEnd('/') }
            val rootFilter = settings.musicRoot.value?.trimEnd('/')
            val rootEnabled = !rootFilter.isNullOrBlank()
            val list = ArrayList<Song>()
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DATE_MODIFIED
            )
            val sort = "${MediaStore.Audio.Media.DISPLAY_NAME} ASC"
            runCatching {
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, sort
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    while (cursor.moveToNext()) {
                        val data = cursor.getString(dataCol) ?: continue
                        val pathFile = File(data)
                        val ext = pathFile.extension.lowercase()
                        if (ext !in supportedExt) continue
                        if (rootEnabled && !data.startsWith(rootFilter!!)) continue
                        if (ignored.any { data.startsWith(it) }) continue
                        val id = cursor.getLong(idCol)
                        val artUri = ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"),
                            cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID).let { cursor.getLong(it) }
                        )
                        list.add(
                            Song(
                                id = "local:$data",
                                title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: pathFile.nameWithoutExtension,
                                artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() } ?: "未知歌手",
                                album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() },
                                pic = artUri.toString(),
                                durationMs = cursor.getLong(durationCol),
                                source = SongSource.LOCAL,
                                localPath = data
                            )
                        )
                    }
                }
            }
            _songs.value = list
        } finally {
            _isScanning.value = false
        }
    }
}