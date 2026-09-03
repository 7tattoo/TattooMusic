package com.spotify.music.data.local

import com.spotify.music.data.model.LyricLine

/** Parses standard LRC text into synchronized lyric lines. */
object LrcTextParser {

    private val lineRegex = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<LyricLine>()
        // A line may carry multiple timestamps before the text.
        for (line in raw.lines()) {
            val matches = lineRegex.findAll(line).toList()
            if (matches.isEmpty()) continue
            val text = line.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (m in matches) {
                val min = m.groupValues[1].toLongOrNull() ?: 0L
                val sec = m.groupValues[2].toLongOrNull() ?: 0L
                val fracRaw = m.groupValues[3]
                val frac = when {
                    fracRaw.isBlank() -> 0L
                    fracRaw.length == 2 -> fracRaw.toLongOrNull() ?: 0L
                    else -> (fracRaw.take(3) + "00").take(3).toLongOrNull() ?: 0L
                }
                val ms = min * 60_000 + sec * 1000 + frac
                out.add(LyricLine(ms, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }
}