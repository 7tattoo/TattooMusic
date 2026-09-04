package com.spotify.music.data.api

/**
 * Parsed MPEG audio frame header. `versionBits` uses the raw 2-bit MPEG version
 * field: 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5. `layer` is 3 = Layer I, 2 = Layer II,
 * 1 = Layer III (raw 2-bit layer field 1/2/3).
 */
class Mp3Info(
    val versionBits: Int,
    val layer: Int,
    val bitrateKbps: Int,
    val sampleRate: Int,
    val samplesPerFrame: Int,
    val frameLength: Int,
    val channelMode: Int,   // 0=stereo,1=joint,2=dual,3=mono
    val frameOffset: Int,   // byte offset of the first frame sync within the chunk
    val hasCrc: Boolean
)

/**
 * Pure (network-free) MP3 duration parsing. Extracts the real track length from
 * the first MPEG frame: prefers the Xing/Info (VBR) frame count, then the
 * Fraunhofer VBRI header, then a CBR bitrate estimate from the total byte size.
 *
 * Only the first ~128–192 KB plus an ID3 size field are needed, so it works over
 * a cheap HTTP Range request and never downloads the whole file.
 */
object Mp3Heuristics {

    // Bitrate tables in kbps indexed by the 4-bit bitrate index (0..14).
    // [0]=Layer I, [1]=Layer II, [2]=Layer III for MPEG1.
    private val BR_MPEG1 = arrayOf(
        intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448),
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384),
        intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    )
    // MPEG2/2.5: [0]=Layer I, [1]=Layer II & III.
    private val BR_MPEG2 = arrayOf(
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256),
        intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    )

    private val SR_MPEG1 = intArrayOf(44100, 48000, 32000)
    private val SR_MPEG2 = intArrayOf(22050, 24000, 16000)
    private val SR_MPEG25 = intArrayOf(11025, 12000, 8000)

    /** ID3v2 tag total size (header + tag body) or null when not an ID3v2 file. */
    fun id3v2Size(chunk: ByteArray): Int? {
        if (chunk.size < 10) return null
        if (u8(chunk, 0) != 'I'.code || u8(chunk, 1) != 'D'.code || u8(chunk, 2) != '3'.code) return null
        return synchsafe(chunk, 6) + 10
    }

    /** 4-byte big-endian (each byte masked to 7 bits) ID3 synchsafe integer. */
    fun synchsafe(c: ByteArray, i: Int): Int =
        ((u8(c, i) and 0x7F) shl 21) or
            ((u8(c, i + 1) and 0x7F) shl 14) or
            ((u8(c, i + 2) and 0x7F) shl 7) or
            (u8(c, i + 3) and 0x7F)

    /**
     * Locate the first valid MPEG audio frame header in [chunk], skipping any
     * leading ID3v2 tag. Returns null when no valid frame is found (or the ID3
     * tag runs past the sampled data).
     */
    fun findFirstFrame(chunk: ByteArray): Mp3Info? {
        var base = 0
        val id3 = id3v2Size(chunk)
        if (id3 != null && id3 <= chunk.size - 4) base = id3
        else if (id3 != null) return null // tag larger than the sampled chunk
        for (i in base until chunk.size - 3) {
            if (!isSync(chunk, i)) continue
            val info = parseHeaderAt(chunk, i) ?: continue
            return info
        }
        return null
    }

    private fun isSync(c: ByteArray, i: Int): Boolean =
        u8(c, i) == 0xFF && (u8(c, i + 1) and 0xE0) == 0xE0

    private fun parseHeaderAt(c: ByteArray, i: Int): Mp3Info? {
        val b2 = u8(c, i + 1)
        val b3 = u8(c, i + 2)
        val b4 = u8(c, i + 3)

        val versionBits = (b2 shr 3) and 3
        if (versionBits == 1) return null                 // reserved
        val layerBits = (b2 shr 1) and 3
        if (layerBits == 0) return null                  // reserved
        // Raw 2-bit layer field 01 = Layer III, 10 = Layer II, 11 = Layer I.
        val layer = layerBits                            // 1=III, 2=II, 3=I
        val hasCrc = (b2 and 1) == 0
        val brIdx = (b3 shr 4) and 0xF
        if (brIdx == 0 || brIdx == 15) return null       // free / invalid bitrate
        val sri = (b3 shr 2) and 3
        if (sri == 3) return null                        // invalid sample rate
        val pad = (b3 shr 1) and 1
        val channelMode = (b4 shr 6) and 3

        val bitrate = when (versionBits) {
            3 -> BR_MPEG1[3 - layer][brIdx]
            else -> BR_MPEG2[if (layer == 3) 0 else 1][brIdx]
        }
        val sampleRate = when (versionBits) {
            3 -> SR_MPEG1[sri]
            2 -> SR_MPEG2[sri]
            else -> SR_MPEG25[sri]
        }

        val spf = when (layer) {
            3 -> 384                                       // Layer I
            1 -> if (versionBits == 3) 1152 else 576      // Layer III
            else -> 1152                                  // Layer II
        }

        // Frame length in bytes: 144*bitrate*1000/sampleRate (MPEG1 L2/L3),
        // 72*... (MPEG2/2.5 L2/L3), (12*bitrate*1000/sampleRate+pad)*4 (L1).
        val frameLength = when {
            layer == 3 -> (12 * bitrate * 1000 / sampleRate + pad) * 4
            versionBits == 3 -> 144 * bitrate * 1000 / sampleRate + pad
            else -> 72 * bitrate * 1000 / sampleRate + pad
        }

        return Mp3Info(versionBits, layer, bitrate, sampleRate, spf, frameLength, channelMode, i, hasCrc)
    }

    /** Estimated/parsed duration in milliseconds, or null when it cannot be known. */
    fun parseDurationMs(chunk: ByteArray, totalBytes: Long): Long? {
        val f = findFirstFrame(chunk) ?: return null
        val p = f.frameOffset

        // Xing / Info VBR frame-count tag (Layer III only), placed right after the
        // frame header + CRC + side-info.
        if (f.layer == 1) {
            val side = when {
                f.versionBits == 3 -> if (f.channelMode == 3) 17 else 32   // MPEG1
                else -> if (f.channelMode == 3) 9 else 17                  // MPEG2/2.5
            }
            val xArg = p + 4 + (if (f.hasCrc) 2 else 0) + side
            for (k in xArg until minOf(xArg + 64, chunk.size - 8)) {
                val name = name4(chunk, k)
                if (name == "Xing" || name == "Info") {
                    val flags = beInt(chunk, k + 4)
                    if ((flags and 1) != 0) {
                        val frames = beInt(chunk, k + 8)
                        if (frames > 0) return msFromFrames(frames, f.sampleRate, f.samplesPerFrame)
                    }
                    if ((flags and 2) != 0) {
                        val bytes = beLong(chunk, k + 12)
                        if (bytes > 0 && totalBytes > 0) return msFromBytes(minOf(bytes, totalBytes), f.bitrateKbps)
                    }
                    break
                }
            }
        }

        // Fraunhofer VBRI (Layer III, placed near the start of the first frame).
        for (k in p + 4 until minOf(p + 40, chunk.size - 4)) {
            if (u8(chunk, k) == 'V'.code && u8(chunk, k + 1) == 'B'.code && u8(chunk, k + 2) == 'R'.code && u8(chunk, k + 3) == 'I'.code) {
                val frames = beInt(chunk, k + 18)
                if (frames > 0) return msFromFrames(frames, f.sampleRate, f.samplesPerFrame)
                val bytes = beLong(chunk, k + 14)
                if (bytes > 0 && totalBytes > 0) return msFromBytes(bytes, f.bitrateKbps)
                break
            }
        }

        // Constant-bitrate fallback: total bytes vs frame bitrate.
        if (f.bitrateKbps > 0 && totalBytes > f.frameOffset) {
            val dataBytes = maxOf(0L, totalBytes - f.frameOffset)
            return msFromBytes(dataBytes, f.bitrateKbps)
        }
        return null
    }

    private fun msFromFrames(frames: Int, sampleRate: Int, spf: Int): Long =
        frames.toLong() * spf * 1000L / sampleRate

    /** ms = bytes * 8 / kbps (each second is kbps*1000/8 bytes). */
    private fun msFromBytes(bytes: Long, kbps: Int): Long = bytes * 8L / kbps

    private fun name4(c: ByteArray, i: Int): String =
        buildString {
            if (i + 3 < c.size) {
                for (j in 0..3) append((u8(c, i + j)).toChar())
            }
        }

    private fun u8(c: ByteArray, i: Int): Int = if (i in c.indices) c[i].toInt() and 0xFF else 0

    private fun beInt(c: ByteArray, i: Int): Int =
        (u8(c, i) shl 24) or (u8(c, i + 1) shl 16) or (u8(c, i + 2) shl 8) or u8(c, i + 3)

    private fun beLong(c: ByteArray, i: Int): Long =
        (0xFFL and u8(c, i).toLong() shl 24) or
            (0xFFL and u8(c, i + 1).toLong() shl 16) or
            (0xFFL and u8(c, i + 2).toLong() shl 8) or
            (0xFFL and u8(c, i + 3).toLong())
}