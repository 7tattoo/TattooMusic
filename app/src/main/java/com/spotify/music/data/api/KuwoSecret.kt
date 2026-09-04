package com.spotify.music.data.api

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
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

    /**
     * Neutral UA sent on public (secret-free) kuwo endpoints
     * (search.kuwo.cn, antiserver.kuwo.cn, m.kuwo.cn). Deliberately does NOT
     * reference [headers]/[secret]: the Secret hash is only needed by the
     * www.kuwo.cn API and computing it has no bearing on these endpoints.
     */
    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * Desktop Chrome UA used by the www.kuwo.cn web API. The cookie and Secret in
     * [headers] belong to a desktop browser session, and kuwo rejects requests
     * whose User-Agent doesn't look like a desktop web browser (returns
     * "The request is illegal!"). Do NOT use [MOBILE_UA] on the web API.
     */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Mirrors JS `String.prototype.charAt`: returns an empty string for an
     * out-of-range index instead of throwing. The reference algorithm relies on
     * this (see kuwoMusicApi app/utils/secret.js): when nStr.length is a multiple
     * of 5, `nStr.charAt(5*r)` yields "" and `parseInt` still parses the shorter
     * digit string. Using `[]` here would throw and abort Secret generation for
     * those live tokens, silently falling back to a stale cookie/secret.
     */
    private fun charAt(s: String, i: Int): Char? =
        if (i in s.indices) s[i] else null

    private fun h(t: String, e: String, rand: Random = Random.Default): String {
        if (e.isEmpty()) throw IllegalStateException("empty password")
        val nStr = buildString { for (ch in e) append(ch.code) }
        val r = nStr.length / 5
        val o = buildString {
            charAt(nStr, r)?.let { append(it) }
            charAt(nStr, 2 * r)?.let { append(it) }
            charAt(nStr, 3 * r)?.let { append(it) }
            charAt(nStr, 4 * r)?.let { append(it) }
            charAt(nStr, 5 * r)?.let { append(it) }
        }.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            ?: throw IllegalStateException("bad hash")
        val l = ceil(e.length / 2.0)
        val c = 2147483647.0 // 2^31 - 1
        if (o < 2.0) throw IllegalStateException("bad hash")
        val d0 = (1e9 * rand.nextDouble()).toLong() % 100_000_000L
        // JS: `n = nStr + d` is STRING concatenation, then the magnitude is
        // reduced by repeatedly parsing the leading 10 digits and the remaining
        // digits with parseInt (which, unlike Long.toLong(), never overflows for
        // 100+ digit strings — it returns a lossy IEEE-754 double, exactly like
        // the reference secret.js). This is mirrored by [jsParseInt]/[jsToString].
        var n = nStr + d0
        var guard = 0
        while (n.length > 10 && guard < 100) {
            val a = jsParseInt(n.substring(0, 10))
            val b = jsParseInt(n.substring(10))
            n = jsToString(a + b)
            guard++
        }
        var nnum = n.toDouble()
        nnum = (o * nnum + l) % c
        val out = StringBuilder()
        for (i in t.indices) {
            val normalised = floor(nnum / c * 255.0).toInt()
            val hh = (t[i].code xor normalised) and 0xFF
            out.append(if (hh < 16) "0${hh.toString(16)}" else hh.toString(16))
            nnum = (o * nnum + l) % c
        }
        var dHex = d0.toString(16)
        while (dHex.length < 8) dHex = "0$dHex"
        return out.toString() + dHex
    }

    /**
     * Mirrors JS `parseInt`: reads the maximal leading run of digits (ignoring a
     * leading sign and whitespace) and returns the value as an IEEE-754 double.
     * For a 100+ digit string the result is the nearest double, NOT a throw —
     * this is the crucial difference from [Long]/[BigInteger] parsing that the
     * reference [h] relies on.
     */
    private fun jsParseInt(s0: String): Double {
        var i = 0
        while (i < s0.length && s0[i].isWhitespace()) i++
        var sign = 1.0
        if (i < s0.length && (s0[i] == '+' || s0[i] == '-')) {
            if (s0[i] == '-') sign = -1.0
            i++
        }
        val start = i
        while (i < s0.length && s0[i].isDigit()) i++
        if (i == start) return Double.NaN
        return (s0.substring(start, i).toLongOrNull()?.toDouble()
            ?: s0.substring(start, i).toDouble()) * sign
    }

    /**
     * Mirrors JS `Number.prototype.toString()` for the exact set of values the
     * reduction loop produces (always integers): integers in decimal form below
     * 1e21, exponential "d.dddde±NN" form at or above 1e21. The mantissa digits
     * come from the shortest-round-trip used by [Double.toString], which agrees
     * with JS for these magnitudes.
     */
    private fun jsToString(v: Double): String {
        if (v.isNaN()) return "NaN"
        if (v == Double.POSITIVE_INFINITY) return "Infinity"
        if (v == Double.NEGATIVE_INFINITY) return "-Infinity"
        if (v == 0.0) return "0"
        val a = abs(v)
        val e10 = floor(log10(a)).toLong()
        if (v == floor(v)) { // integer value
            if (e10 < 21) {
                return if (a <= 9.2e18) v.toLong().toString()
                else java.math.BigDecimal(v).toBigInteger().toString()
            }
            return formatExp(v)
        }
        return v.toString() // not reached by [h] but kept for exactness
    }

    /**
     * JS-style exponential form for a value >= 1e21, e.g. "1.7118116959752997e+85".
     * Rebuilds the mantissa from [Double.toString]'s shortest digits and normalises
     * the exponent to lowercase 'e' with a '+'/'-' sign and no padding.
     */
    private fun formatExp(v: Double): String {
        val base = v.toString() // e.g. "1.7118116959752997E85"
        val lower = base.toUpperCase().replace('E', 'e')
        var mant = lower.substringBefore('e')
        if (mant.contains('.')) {
            val ip = mant.substringBefore('.')
            val fp = mant.substringAfter('.').trimEnd('0')
            mant = if (fp.isEmpty()) ip else "$ip.$fp"
        }
        val expPart = lower.substringAfter('e', "")
        val neg = expPart.startsWith('-')
        val expNum = expPart.trimStart('+', '-').toLong()
        return "${mant}e${if (neg) "-" else "+"}${abs(expNum)}"
    }

    /** The value to send in the Secret header. Computed defensively so it never throws. */
    val secret: String by lazy { runCatching { h(fValue, F) }.getOrNull() ?: "0" }

    /**
     * Compute a Secret for a live token pair (e.g. a freshly rotated
     * `Hm_Iuvt_...` cookie). Returns null when the token cannot be hashed
     * (too short / index overflow), so callers can fall back to the static one.
     */
    fun secretFor(tokenName: String, tokenValue: String, rand: Random = Random.Default): String? =
        runCatching { h(tokenValue, tokenName, rand) }.getOrNull()

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