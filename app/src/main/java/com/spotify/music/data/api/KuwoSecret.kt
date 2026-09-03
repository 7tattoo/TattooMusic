package com.spotify.music.data.api

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random

/**
 * Replicates the request auth header used by the public kuwoMusicApi proxy
 * (see https://github.com/qyhqiu/kuwoMusicApi app/utils/secret.js). The www.kuwo.cn
 * API requires a "Secret" header plus a cookie.
 */
object KuwoSecret {
    private const val F = "Hm_Iuvt_cdb524f42f0ce19b169b8072123a4727"
    const val COOKIE =
        "Hm_lvt_cdb524f42f0ce19b169a8071123a4797=1689780885000; _ga=GA1.2.259721034.1689780885000; " +
        "_gid=GA1.2.1715768254.1689780885000; Hm_lpvt_cdb524f42f0ce19b169a8071123a4797=1689780885000; " +
        "_ga_ETPBRPM9ML=GS1.2.1689780885000.2.0.1689780885000.60.0.0; " +
        "Hm_Iuvt_cdb524f42f0ce19b169b8072123a4727=3MiWHX6n8Zr8sN48sF3dccyTWjZ54Hxy"

    /** Extract the cookie value for [key] from [COOKIE]. */
    private fun pick(key: String): String {
        val idx = COOKIE.indexOf("$key=")
        if (idx == -1) return ""
        var end = idx + key.length + 1
        val semi = COOKIE.indexOf(';', end)
        if (semi == -1) end = COOKIE.length else end = semi
        return COOKIE.substring(idx + key.length + 1, end)
    }

    /** The value of @param f used as the hashing password. */
    private val fValue: String = pick(F)

    private fun h(t: String, e: String, rand: Random = Random.Default): String {
        if (e.isEmpty()) throw IllegalStateException("empty password")
        val digits = StringBuilder()
        for (ch in e) digits.append(ch.code)
        val nStr = digits.toString()
        val r = nStr.length / 5
        val o = buildString {
            append(nStr[r]); append(nStr[2 * r]); append(nStr[3 * r]); append(nStr[4 * r]); append(nStr[5 * r])
        }.toLong()
        val l = ceil(e.length / 2.0).toLong()
        val c = (1L shl 31) - 1L
        if (o < 2L) throw IllegalStateException("bad hash")
        var d = (1e9 * rand.nextDouble()).toLong() % 100_000_000L
        var seed = nStr + d
        while (seed.length > 10) {
            val a = seed.substring(0, 10).toLong()
            val b = seed.substring(10).toLong()
            seed = (a + b).toString()
        }
        var n = (o * nStr.toLong() + l) % c
        val out = StringBuilder()
        for (i in t.indices) {
            val normalised = floor(n.toDouble() / c * 255.0).toInt()
            val hh = (t[i].code xor normalised)
            out.append(if (hh < 16) "0${hh.toString(16)}" else hh.toString(16))
            n = (o * n + l) % c
        }
        var dHex = d.toString(16)
        while (dHex.length < 8) dHex = "0$dHex"
        return out.toString() + dHex
    }

    /** The value to send in the Secret header. */
    val secret: String by lazy { h(fValue, F) }

    val headers: Map<String, String> by lazy {
        mapOf(
            "Cookie" to COOKIE,
            "Secret" to secret,
            "Host" to "www.kuwo.cn",
            "Referer" to "https://www.kuwo.cn/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.77 Safari/537.36"
        )
    }
}