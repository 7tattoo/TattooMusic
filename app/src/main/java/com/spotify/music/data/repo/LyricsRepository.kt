package com.spotify.music.data.repo

import com.spotify.music.data.api.KuwoApi
import com.spotify.music.data.local.EmbeddedLyricsReader
import com.spotify.music.data.local.LrcTextParser
import com.spotify.music.data.model.LyricLine
import com.spotify.music.data.model.Song
import com.spotify.music.data.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves lyrics for a song depending on its source:
 * online songs pull LRC from kuwo, local songs read embedded/sidecar LRC files.
 */
class LyricsRepository(
    private val api: KuwoApi,
    private val embeddedReader: EmbeddedLyricsReader
) {
    suspend fun load(song: Song): List<LyricLine> {
        return when (song.source) {
            SongSource.LOCAL -> parseLocal(song)
            SongSource.ONLINE -> api.getLyrics(song.id)
        }
    }

    private suspend fun parseLocal(song: Song): List<LyricLine> =
        withContext(Dispatchers.IO) {
            val path = song.localPath?.takeIf { it.isNotEmpty() }
            val raw = path?.let { embeddedReader.readLyrics(it) }
            if (!raw.isNullOrBlank()) {
                val lines = LrcTextParser.parse(raw)
                if (lines.isNotEmpty()) {
                    // Unsynchronized embedded lyrics (e.g. a FLAC LYRICS tag with
                    // plain text, no [mm:ss] tags) parse to lines all at time 0, so
                    // the scroller has no timeline and can't scroll. Synthesize an
                    // even spacing across the track duration to make them scroll.
                    if (lines.all { it.timeMs == 0L }) {
                        return@withContext synthesizeTiming(lines, song.durationMs)
                    }
                    return@withContext lines
                }
            }
            // Network fallback: match the song by title (and artist) and pull lyrics.
            val keyword = listOfNotNull(song.title, song.artist)
                .joinToString(" ") { it.trim() }.trim()
            if (keyword.isNotEmpty()) {
                runCatching {
                    api.searchSongs(keyword, 1, 3).firstOrNull()?.let { hit ->
                        api.getLyrics(hit.id)
                    }
                }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
            }
            emptyList()
        }

    /** Evenly distribute untimed lines across the song duration so they scroll. */
    private fun synthesizeTiming(lines: List<LyricLine>, durationMs: Long): List<LyricLine> {
        if (lines.isEmpty()) return lines
        val n = lines.size
        if (durationMs <= 0) {
            // No known duration: step each line by 3s so the scroller still advances.
            return lines.mapIndexed { i, l -> LyricLine(3000L * i, l.text) }
        }
        val leadIn = 1500L // small gap at the start
        val span = (durationMs - leadIn).coerceAtLeast(0L)
        return lines.mapIndexed { i, l -> LyricLine(leadIn + span * i / n, l.text) }
    }

    /** Reconstruct a standard LRC string from parsed lines (for car screen lyrics). */
    fun toLrcText(lines: List<LyricLine>): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("[ti:]\n[ar:]\n")
        for (l in lines) {
            sb.append("[${formatTime(l.timeMs)}]").append(l.text).append('\n')
        }
        return sb.toString()
    }

    private fun formatTime(ms: Long): String {
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        val c = (ms % 1000) / 10
        return "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}.${c.toString().padStart(2, '0')}"
    }
}