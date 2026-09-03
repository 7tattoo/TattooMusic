package com.spotify.music.data.api

import com.spotify.music.data.model.Comment
import com.spotify.music.data.model.FavoritePlaylist
import com.spotify.music.data.model.LyricLine
import com.spotify.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Direct (embedded) Kuwo music API client. Models the endpoints exposed by
 * kuwoMusicApi so the app runs standalone without a proxy server.
 * All parsing is intentionally lenient because upstream fields vary.
 */
class KuwoApi(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val client: OkHttpClient = defaultClient
) {

    /** In-memory cookie store so a warmup visit persists the live session. */
    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val existing = cookieStore.getOrPut(host) { mutableListOf() }
            for (c in cookies) {
                val it = existing.indexOfFirst { it.name == c.name }
                if (it >= 0) existing[it] = c else existing.add(c)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookieStore[url.host]?.toList() ?: emptyList()
    }

    /** Client with cookie persistence, used for all www.kuwo.cn API calls. */
    private val sessionClient: OkHttpClient = client.newBuilder()
        .cookieJar(cookieJar)
        .build()

    @Volatile
    private var sessionReady = false

    // ---- account (cookie-based login) overrides ----
    @Volatile
    private var overrideCookie: String? = null

    @Volatile
    private var overrideSecret: String? = null

    /**
     * Apply a logged-in cookie string (e.g. copied from www.kuwo.cn after a
     * web login). A fresh Secret is derived from the live `Hm_Iuvt_...` token
     * inside it. Pass null/blank to drop back to the anonymous session.
     */
    fun setAccountCookie(rawCookie: String?) {
        overrideCookie = rawCookie?.takeIf { it.isNotBlank() }
        overrideSecret = overrideCookie?.let { c ->
            val m = Regex("(Hm_Iuvt_[A-Za-z0-9]+)=([^;]+)").find(c)
            m?.let { KuwoSecret.secretFor(it.groupValues[1], it.groupValues[2].trim()) }
        }
        sessionReady = false
    }

    /**
     * A consistent (cookie, secret) pair for the anonymous session. Preference
     * is the live pair captured during the warmup visit; otherwise the static
     * default pair. The Secret is ALWAYS derived from the same Hm_Iuvt token
     * that is actually sent in the Cookie header -- mixing a live secret with
     * the static cookie makes Kuwo reject the request ("The request is illegal!").
     */
    private fun anonymousPair(): Pair<String, String> {
        val live = cookieStore["www.kuwo.cn"]
        val liveSecret = live
            ?.firstOrNull { it.name.startsWith("Hm_Iuvt_") && it.value.isNotBlank() }
            ?.let { KuwoSecret.secretFor(it.name, it.value) }
        if (liveSecret != null && !live.isNullOrEmpty()) {
            val cookieStr = live.joinToString("; ") { "${it.name}=${it.value}" }
            return cookieStr to liveSecret
        }
        return KuwoSecret.COOKIE to KuwoSecret.secret
    }

    /** Cookie to send on every request: logged-in cookie wins over anonymous. */
    private fun currentCookie(): String = overrideCookie ?: anonymousPair().first

    /**
     * Secret matching the cookie actually sent: derived from the logged-in
     * token when present, otherwise from the anonymous pair. Never mixed.
     */
    private fun currentSecret(): String = when {
        overrideSecret != null -> overrideSecret!!
        else -> anonymousPair().second
    }

    /**
     * Warm up a kuwo session by visiting the homepage. This populates the
     * cookie store with a live `Hm_Iuvt_...` anti-bot token whose value the
     * `anonymousPair()` Secret is derived from.
     */
    private suspend fun ensureSession() {
        if (sessionReady) return
        withContext(Dispatchers.IO) {
            runCatching {
                sessionClient.newCall(
                    Request.Builder().url("https://www.kuwo.cn/")
                        .header("User-Agent", KuwoSecret.headers["User-Agent"] ?: "")
                        .build()
                ).execute().use { it.body?.close() }
            }
            sessionReady = true
        }
    }

    private suspend fun get(path: String, referer: String? = null): JsonElement? = withContext(Dispatchers.IO) {
        ensureSession()
        // Public feeds always go out with the stable anonymous pair first so the
        // home/search/rank screens are never blank, even when a logged-in cookie
        // is stale/expired or rejected by Kuwo.
        val anon = anonymousPair()
        val direct = tryGet(path, referer, anon.first, anon.second)
        if (direct != null) return@withContext direct
        // If the account session is present and the anonymous attempt failed,
        // tentatively try it. (overrideCookie + overrideSecret are derived from
        // the same token, so they always match.)
        if (overrideCookie != null) {
            return@withContext tryGet(path, referer, overrideCookie!!, overrideSecret ?: anon.second)
        }
        null
    }

    private fun tryGet(path: String, referer: String?, cookie: String, secret: String): JsonElement? {
        return try {
            val url = if (path.startsWith("http")) path else "https://www.kuwo.cn$path"
            val builder = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("Secret", secret)
                .header("Referer", referer ?: "https://www.kuwo.cn/")
                .header("User-Agent", KuwoSecret.headers["User-Agent"] ?: "")
                .header("Accept", "application/json,text/plain,*/*")
            sessionClient.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string() ?: return@use null
                if (!resp.isSuccessful) return@use null
                runCatching { json.parseToJsonElement(body) }.getOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Search songs by keyword (secret-free mobile endpoint). */
    suspend fun searchSongs(keyword: String, pn: Int = 1, rn: Int = 30): List<Song> =
        mobileSearchSongs(keyword, pn, rn)

    /**
     * Search via http://search.kuwo.cn/r.s (web_2013 mobile interface).
     * This endpoint is public and requires no Secret/cookie, unlike the locked
     * www.kuwo.cn API, so search & the home feed work reliably.
     */
    private suspend fun mobileSearchSongs(keyword: String, pn: Int, rn: Int): List<Song> =
        withContext(Dispatchers.IO) {
            val url = "http://search.kuwo.cn/r.s" +
                "?all=${urlEncode(keyword)}&ft=music&itemset=web_2013&client=kt" +
                "&pn=${(pn - 1).coerceAtLeast(0)}&rn=$rn&rformat=json&encoding=utf8"
            val root = runCatching {
                val body = client.newCall(
                    Request.Builder().url(url)
                        .header("User-Agent", KuwoSecret.headers["User-Agent"] ?: "")
                        .build()
                ).execute().use { it.body?.string() }
                val clean = body?.let { b ->
                    // search.kuwo.cn returns JSON with single-quoted keys/values,
                    // which the standard parser rejects. Convert to legal JSON.
                    if (b.contains('\'')) sanitizeSingleQuotes(b) else b
                } ?: "{}"
                json.parseToJsonElement(clean)
            }.getOrNull()
            val arr = root?.j("abslist") ?: return@withContext emptyList()
            val out = ArrayList<Song>()
            for (item in arr.arrayOrList) songFromMobile(item)?.let(out::add)
            out
        }

    private fun songFromMobile(o: JsonElement): Song? {
        val id = o.str("DC_TARGETID") ?: return null
        val title = (o.str("NAME") ?: o.str("SONGNAME"))
            ?.replace("&nbsp;", " ")?.trim() ?: return null
        val artist = o.str("ARTIST") ?: "未知歌手"
        val album = o.str("ALBUM")?.takeIf { it.isNotBlank() }
        val durSec = o.long("DURATION") ?: 0L
        return Song(
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = durSec * 1000
        )
    }

    /** Resolve the playable stream URL for a song id. */
    suspend fun getPlayUrl(mid: String, br: String = "128kmp3"): String? {
        // Prefer the secret-free antiserver endpoint.
        mobilePlayUrl(mid)?.let { return it }
        // Fall back to the legacy www api (requires Secret; often unavailable).
        val el = get("/api/v1/www/music/playUrl?mid=$mid&type=music&httpsStatus=1&plat=web_www&from=&br=$br")
            ?: return null
        val data = el.j("data") ?: return null
        val direct = data.str("url")
        if (!direct.isNullOrEmpty() && direct.startsWith("http")) return direct
        data.arrayOfObjects()?.forEach { q ->
            val u = q.str("url")
            if (!u.isNullOrEmpty() && u.startsWith("http")) return u
        }
        return null
    }

    private suspend fun mobilePlayUrl(mid: String): String? = withContext(Dispatchers.IO) {
        val rid = if (mid.startsWith("MUSIC_")) mid else "MUSIC_$mid"
        val url = "http://antiserver.kuwo.cn/anti.s?type=convert_url3&rid=$rid&response=url&format=mp3&br=128kmp3&apiversion=3"
        runCatching {
            val body = client.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", KuwoSecret.headers["User-Agent"] ?: "")
                    .build()
            ).execute().use { it.body?.string() }
            val root = json.parseToJsonElement(body ?: "{}")
            root.str("url")?.takeIf { it.startsWith("http") }
        }.getOrNull()
    }

    /** Fetch standard LRC lyrics (new h5 endpoint, stable & public). */
    suspend fun getLyrics(musicId: String): List<LyricLine> {
        val url = "https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=${urlEncode(musicId)}&httpsStatus=1"
        // m.kuwo.cn is cookie-independent; sending a logged-in account cookie can
        // make it return an empty lrclist, so use a neutral cookie here.
        var raw = fetchLrc(url, KuwoSecret.COOKIE)
        if (raw.isNullOrBlank() && overrideCookie != null) {
            raw = fetchLrc(url, "")
        }
        return parseLrcJson(raw ?: return emptyList())
    }

    private fun fetchLrc(url: String, cookie: String): String? =
        runCatching {
            client.newCall(
                Request.Builder().url(url)
                    .header("Cookie", cookie)
                    .header("User-Agent", KuwoSecret.headers["User-Agent"] ?: "")
                    .build()
            ).execute().use { it.body?.string() }
        }.getOrNull()

    /** Parse the newh5 lrclist into LyricLine list. */
    private fun parseLrcJson(raw: String): List<LyricLine> {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        val lrclist = root.j("data")?.j("lrclist") ?: return emptyList()
        val out = ArrayList<LyricLine>()
        val timeRegex = Regex("""(\d{1,2}):(\d{1,2})(?:\.(\d{1,3}))?""")
        for (item in lrclist.arrayOrList) {
            val t = item.str("time") ?: continue
            val text = item.str("lineLyric") ?: continue
            val timeMs = parseKuwolrcTime(t, timeRegex) ?: continue
            out.add(LyricLine(timeMs, text.trim()))
        }
        return out.sortedBy { it.timeMs }
    }

    /**
     * Kuwo's h5 lrc endpoint returns `time` either as mm:ss(.cc) or as plain
     * decimal seconds ("0.0", "1.5", "23.999"). Handle both so no line is
     * silently dropped (which previously made every song show "no lyrics").
     */
    private fun parseKuwolrcTime(t: String, r: Regex): Long? {
        val m = r.find(t)
        if (m != null) {
            val mm = m.groupValues[1].toLongOrNull() ?: 0L
            val ss = m.groupValues[2].toLongOrNull() ?: 0L
            val frac = m.groupValues[3].takeIf { it.isNotBlank() }?.let { (it + "00").take(3).toLongOrNull() } ?: 0L
            return mm * 60_000 + ss * 1000 + frac
        }
        val secs = t.toDoubleOrNull()
        return if (secs != null) (secs * 1000).toLong() else null
    }

    /** Recommended playlists (推荐歌单). */
    suspend fun recPlaylists(pn: Int = 1, rn: Int = 12): List<FavoritePlaylist> {
        val el = get("/api/www/rcm/index/playlist?id=0&pn=$pn&rn=$rn&httpsStatus=1") ?: return emptyList()
        val data = el.j("data") ?: return emptyList()
        val arr = data.j("playlistList")
            ?: data.j("list")
            ?: data.jsonArrayOrNull()
            ?: return emptyList()
        val out = ArrayList<FavoritePlaylist>()
        for (item in arr.arrayOrList) {
            val id = item.str("id") ?: item.str("listid") ?: continue
            val name = item.str("name") ?: item.str("playlistName") ?: continue
            val pic = item.str("pic") ?: item.str("img") ?: item.str("cover") ?: item.str("pic120")
            val plays = item.long("listencnt") ?: item.long("playCount") ?: 0L
            out.add(FavoritePlaylist(id, name, pic, plays))
        }
        return out
    }

    /** Top-level rank/bang list. Returns list of {id, name}. */
    suspend fun recBangList(): List<FavoritePlaylist> {
        val el = get("/api/www/bang/index/bangList?&httpsStatus=1", referer = "https://www.kuwo.cn/rankList")
            ?: return emptyList()
        val arr = el.j("data")?.jsonArrayOrNull() ?: return emptyList()
        val out = ArrayList<FavoritePlaylist>()
        for (item in arr.arrayOrList) {
            val id = item.str("bangId") ?: item.str("id") ?: continue
            val name = item.str("name") ?: item.str("bname") ?: continue
            val pic = item.str("pic") ?: item.str("pic120")
            out.add(FavoritePlaylist(id, name, pic, item.long("Total") ?: 0L))
        }
        return out
    }

    /** Songs of a bang/rank. */
    suspend fun rankMusic(bangId: String, pn: Int = 1, rn: Int = 20): List<Song> {
        val el = get("/api/www/bang/bang/musicList?bangId=$bangId&pn=$pn&rn=$rn&httpsStatus=1",
            referer = "https://www.kuwo.cn/rankList") ?: return emptyList()
        return extractSongs(el)
    }

    /** Songs inside a playlist (歌单). Supports "kw:<keyword>" playlists. */
    suspend fun playlistSongs(pid: String, pn: Int = 1, rn: Int = 99): List<Song> {
        if (pid.startsWith("kw:")) {
            return mobileSearchSongs(pid.removePrefix("kw:"), 1, 40)
        }
        val el = get("/api/www/playlist/playListInfo?pid=${urlEncode(pid)}&pn=$pn&rn=$rn&httpsStatus=1")
            ?: return emptyList()
        return extractSongs(el)
    }

    /** Download a URL as raw bytes (used by the download feature). */
    suspend fun rawBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            client.newCall(
                Request.Builder().url(url)
                    .header("Cookie", currentCookie())
                    .header("User-Agent", KuwoSecret.headers["User-Agent"] ?: "")
                    .build()
            ).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Music comments. */
    suspend fun comments(sid: String, page: Int = 1, rows: Int = 20): List<Comment> {
        val url = "https://www.kuwo.cn/comment?type=15&f=web&page=$page&rows=$rows&digest=15&sid=$sid&uid=0&prod=newWeb&httpsStatus=1"
        val el = runCatching {
            withContext(Dispatchers.IO) {
                ensureSession()
                json.parseToJsonElement(
                    sessionClient.newCall(
                        Request.Builder().url(url)
                            .header("Cookie", currentCookie())
                            .header("Secret", currentSecret())
                            .header("Referer", "https://www.kuwo.cn/play_detail/${urlEncode(sid)}")
                            .header("User-Agent", KuwoSecret.headers["User-Agent"] ?: "")
                            .build()
                    ).execute().use { it.body?.string() ?: "" }
                )
            }
        }.getOrNull() ?: return emptyList()
        val arr = el.j("data")?.j("commentList") ?: return emptyList()
        val out = ArrayList<Comment>()
        for (item in arr.arrayOrList) {
            val content = item.str("comContent") ?: item.str("msg") ?: continue
            val user = item.str("userName") ?: item.str("nickname") ?: "用户"
            val avatar = item.str("userPic") ?: item.str("avatar")
            val like = item.long("likeNum")?.toInt() ?: 0
            val time = item.long("time")?.let { formatTs(it) } ?: item.str("date") ?: ""
            val id = item.str("id") ?: content.hashCode().toString()
            out.add(Comment(user, avatar, content, time, like, id))
        }
        return out
    }

    // ---------------- song extraction ----------------
    private fun extractSongs(root: JsonElement): List<Song> {
        val data = root.j("data") ?: return emptyList()
        val arr = data.jsonArrayOrNull()
            ?: root.jsonArrayOrNull()
            ?: return emptyList()
        val out = ArrayList<Song>()
        for (item in arr.arrayOrList) {
            val song = songFrom(item) ?: continue
            out.add(song)
        }
        return out
    }

    private fun songFrom(o: JsonElement): Song? {
        val id = o.str("rid") ?: o.str("musicrid")?.substringAfterLast("_") ?: o.str("id") ?: return null
        val title = o.str("name") ?: o.str("songName") ?: o.str("title") ?: return null
        val artist = o.str("artist") ?: o.str("singer") ?: o.str("artistName") ?: "未知歌手"
        val album = o.str("album") ?: o.str("albumName")
        val pic = o.str("pic") ?: o.str("pic120") ?: o.str("albumpic") ?: o.str("albumpic_big")
        val durSec = o.long("duration") ?: o.long("songTimeMinutes") ?: 0L
        val durMs = if (durSec < 600) durSec * 1000 else durSec
        return Song(
            id = id,
            title = title,
            artist = artist,
            album = album,
            pic = pic,
            durationMs = durMs
        )
    }

    private fun formatTs(sec: Long): String {
        val d = java.time.Instant.ofEpochMilli(sec * 1000).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return d.toString()
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

// ---------- small JSON helpers (lenient) ----------
private fun JsonElement.j(name: String): JsonElement? =
    (this as? JsonObject)?.get(name)?.takeIf { it !is JsonPrimitive || it.jsonPrimitive.content != "null" }

private fun JsonElement.str(name: String): String? {
    val v = j(name) as? JsonPrimitive ?: return null
    return v.content.takeIf { it.isNotBlank() }
}

private fun JsonElement.long(name: String): Long? {
    val v = j(name) as? JsonPrimitive ?: return null
    val c = v.content
    if (c.contains(":")) {
        // duration like "m:ss" -> seconds
        val parts = c.split(":")
        if (parts.size >= 2) {
            val m = parts[0].toLongOrNull() ?: 0L
            return m * 60 + (parts[1].toLongOrNull() ?: 0L)
        }
    }
    return c.toLongOrNull()
}

private fun JsonElement.jsonArrayOrNull(): JsonElement? {
    return when {
        this is kotlinx.serialization.json.JsonArray -> this
        this is JsonObject -> {
            sequenceOf("list", "musicList", "playlistList", "songList")
                .mapNotNull { get(it) }
                .filterIsInstance<kotlinx.serialization.json.JsonArray>()
                .firstOrNull()
        }
        else -> null
    }
}

private val JsonElement.arrayOrList: List<JsonElement>
    get() = (this as? kotlinx.serialization.json.JsonArray)?.toList() ?: emptyList()

private fun JsonElement.arrayOfObjects(): List<JsonElement>? =
    (this as? kotlinx.serialization.json.JsonArray)?.toList()

/**
 * Convert single-quoted JSON (as returned by the public search.kuwo.cn
 * interface) into valid double-quoted JSON. Handles `\'` and `\"` escapes
 * inside strings and leaves already double-quoted sections untouched, so it
 * is safe even if an upstream response mixes quote styles.
 */
internal fun sanitizeSingleQuotes(s: String): String {
    if (s.isEmpty()) return s
    val out = StringBuilder(s.length + 8)
    var inSingle = false
    var inDouble = false
    var i = 0
    val n = s.length
    while (i < n) {
        val c = s[i]
        when {
            c == '\\' && i + 1 < n -> {
                val nxt = s[i + 1]
                when {
                    inDouble -> { out.append(c).append(nxt) }        // preserve both
                    inSingle && nxt == '\'' -> { out.append('\''); i++ } // \' -> '
                    inSingle && nxt == '"' -> { out.append(c).append('"'); i++ } // \" stays
                    else -> { out.append(c).append(nxt); i++ }
                }
            }
            inSingle -> {
                if (c == '\'') { out.append('"'); inSingle = false }
                else if (c == '"') { out.append("\\\"") }
                else out.append(c)
            }
            inDouble -> {
                if (c == '"') { out.append(c); inDouble = false }
                else out.append(c)
            }
            c == '\'' -> { out.append('"'); inSingle = true }
            c == '"' -> { out.append(c); inDouble = true }
            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}