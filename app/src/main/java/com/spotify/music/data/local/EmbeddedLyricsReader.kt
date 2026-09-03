package com.spotify.music.data.local

import java.io.File

/**
 * Reads song lyrics for local audio files. Prefers a sidecar .lrc/.txt file,
 * then falls back to embedded lyrics (MP3 ID3v2 USLT, FLAC Vorbis "LYRICS",
 * MP4/M4A "\u00a9lyr"). Returns raw LRC text when available.
 */
class EmbeddedLyricsReader {

    fun readLyrics(audioPath: String?): String? {
        if (audioPath.isNullOrEmpty()) return null

        // 1) sidecar .lrc / .txt
        val file = File(audioPath)
        val base = file.nameWithoutExtension
        val dir = file.parentFile
        if (dir != null) {
            for (ext in listOf(".lrc", ".LRC", ".txt")) {
                val side = File(dir, base + ext)
                if (side.isFile && side.length() < 1_500_000) {
                    runCatching { side.readText(Charsets.UTF_8) }.let {
                        if (it.isSuccess && it.getOrNull()?.isNotBlank() == true) return it.getOrNull()
                    }
                }
            }
        }

        // 2) embedded lyrics
        return runCatching { extractEmbedded(file) }.getOrNull()
    }

    private fun extractEmbedded(file: File): String? {
        // 限长读取前 12MB，避免大文件 OOM
        val bytes = file.inputStream().buffered().use { ins ->
            val n = minOf(file.length(), 12_000_000L).toInt()
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = ins.read(buf, off, n - off)
                if (r < 0) break
                off += r
            }
            buf
        }
        val ext = file.extension.lowercase()
        return when (ext) {
            "mp3" -> extractMp3Uslt(bytes)
            "flac" -> extractFlacLyrics(bytes)
            "m4a", "mp4", "aac" -> extractNeroLyr(bytes)
            else -> null
        }
    }

    // ------------------ MP3 ID3v2 USLT ------------------
    private fun extractMp3Uslt(b: ByteArray): String? {
        if (b.size < 10 || iso(b, 0, 3) != "ID3") return null
        val major = b[3].toInt() and 0xff
        // syncsafe size
        val size = syncsafe(b, 6, 4)
        val end = minOf(b.size, 10 + size)
        var i = 10
        while (i + 10 <= end) {
            val id = iso(b, i, 4)
            val frameSize = when {
                major >= 4 -> syncsafe(b, i + 4, 4)
                else -> bigEndian(b, i + 4, 4)
            }
            val dataStart = i + 10
            val dataEnd = minOf(end, dataStart + frameSize)
            if (frameSize <= 0) break
            if (id == "USLT") {
                val text = readUslt(b, dataStart, dataEnd) ?: return null
                if (text.isNotBlank()) return text
            }
            i = dataEnd
        }
        return null
    }

    private fun readUslt(b: ByteArray, start: Int, end: Int): String? {
        if (end - start < 4) return null
        val encoding = b[start].toInt() and 0xff
        // skip encoding byte + 3-byte language, then the optional content
        // descriptor (terminated per the text encoding).
        var p = start + 4
        p = skipDescriptor(b, p, end, encoding)
        if (p < 0 || p >= end) return null
        val text = when (encoding) {
            0 -> decodeLatin1(b, p, end)
            1, 2 -> decodeUtf16(b, p, end)
            else -> decodeUtf8(b, p, end)
        }
        // strip trailing NULs / whitespace that frequently pad the frame
        return text.trimStart('\u0000').trimEnd('\u0000').trim()
            .takeIf { it.isNotBlank() }
    }

    /** Skip a NUL-terminated field where the terminator width depends on encoding. */
    private fun skipDescriptor(b: ByteArray, from: Int, end: Int, encoding: Int): Int {
        val twoByte = encoding == 1 || encoding == 2
        // Encoding 1 = UTF-16 with BOM; an empty descriptor may still carry no BOM.
        var i = from
        while (i < end) {
            val c = b[i].toInt() and 0xff
            if (c == 0) {
                // UTF-16 terminator is 0x00 0x00 (BE) or 0x00 0x00 (LE after a BOM).
                if (twoByte && i + 1 < end && (b[i + 1].toInt() and 0xff) == 0) return i + 2
                return i + 1
            }
            i++
        }
        return -1
    }

    // ------------------ FLAC Vorbis comment ------------------
    private fun extractFlacLyrics(b: ByteArray): String? {
        if (b.size < 4 || iso(b, 0, 4) != "fLaC") return null
        var offset = 4
        var header = readIntLE(b, offset)
        var isLast = (header and 0x80000000.toInt()) != 0
        var type = (header ushr 24) and 0x7f
        var length = header and 0x00ffffff
        offset += 4
        while (offset < b.size) {
            if (type == 4) { // VORBIS_COMMENT
                return parseVorbisComments(b, offset, length)
            }
            offset += length
            if (isLast || offset + 4 > b.size) break
            header = readIntLE(b, offset)
            isLast = (header and 0x80000000.toInt()) != 0
            type = (header ushr 24) and 0x7f
            length = header and 0x00ffffff
            offset += 4
        }
        return null
    }

    private fun parseVorbisComments(b: ByteArray, start: Int, length: Int): String? {
        var p = start
        val end = minOf(b.size, start + length)
        if (p + 4 > end) return null
        p += 4 // vendor string length
        if (p + 4 > end) return null
        val count = readIntLE(b, p); p += 4
        for (k in 0 until count) {
            if (p + 4 > end) break
            val len = readIntLE(b, p); p += 4
            if (p + len > end) break
            val comment = decodeUtf8(b, p, p + len)
            p += len
            val idx = comment.indexOf('=')
            if (idx > 0) {
                val key = comment.substring(0, idx).uppercase()
                if (key == "LYRICS" || key == "UNSYNCEDLYRICS") {
                    val v = comment.substring(idx + 1)
                    if (v.isNotBlank()) return v
                }
            }
        }
        return null
    }

    // ------------------ MP4/M4A ©lyr ------------------
    private fun extractNeroLyr(b: ByteArray): String? {
        // locate '©lyr' atom content (usually UTF-8). Simple scan.
        val target = byteArrayOf(0xC2.toByte(), 0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())
        val idx = indexOf(b, target, from = 12)
        if (idx < 0) return null
        var p = idx + target.size
        val end = minOf(b.size, p + 4096)
        val collected = run {
            val sb = StringBuilder()
            while (p < end) {
                val c = b[p].toInt() and 0xff
                if (c == 0) break
                sb.append(c.toChar())
                p++
            }
            sb.toString()
        }
        return collected.takeIf { it.isNotBlank() }
    }

    // ------------------ helpers ------------------
    private fun iso(b: ByteArray, at: Int, len: Int): String {
        val sb = StringBuilder()
        for (k in 0 until len) {
            if (at + k >= b.size) break
            sb.append(b[at + k].toInt().toChar())
        }
        return sb.toString().trim('\u0000')
    }

    private fun syncsafe(b: ByteArray, at: Int, len: Int): Int {
        var v = 0
        for (k in 0 until len) {
            v = (v shl 7) or (b[at + k].toInt() and 0x7f)
        }
        return v
    }

    private fun bigEndian(b: ByteArray, at: Int, len: Int): Int {
        var v = 0
        for (k in 0 until len) {
            v = (v shl 8) or (b[at + k].toInt() and 0xff)
        }
        return v
    }

    private fun readIntLE(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or
            ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or
            ((b[at + 3].toInt() and 0xff) shl 24)

    private fun decodeUtf8(b: ByteArray, s: Int, e: Int): String =
        String(b, s.coerceAtMost(b.size), (e - s).coerceAtLeast(0), Charsets.UTF_8)

    private fun decodeLatin1(b: ByteArray, s: Int, e: Int): String =
        String(b, s.coerceAtMost(b.size), (e - s).coerceAtLeast(0), Charsets.ISO_8859_1)

    private fun decodeUtf16(b: ByteArray, s: Int, e: Int): String {
        if (e - s < 2) return ""
        return if (b[s].toInt() == 0xff.toByte().toInt() && b[s + 1].toInt() == 0xfe.toByte().toInt()) {
            // BOM present, little endian
            String(b, s + 2, (e - s - 2).coerceAtLeast(0), Charsets.UTF_16LE)
        } else {
            // assume UTF-16BE without BOM
            String(b, s, (e - s).coerceAtLeast(0), Charsets.UTF_16BE)
        }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..hay.size - needle.size) {
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}