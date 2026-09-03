package com.spotify.music.data.repo

import com.spotify.music.data.api.KuwoApi
import com.spotify.music.data.model.FavoritePlaylist
import com.spotify.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Aggregated grouping of the home page. */
data class HomeData(
    val dailyRecommend: List<Song> = emptyList(),
    val guessYouLike: List<Song> = emptyList(),
    val recommendPlaylists: List<FavoritePlaylist> = emptyList()
)

/**
 * Builds the 首页 data. The www.kuwo.cn web API is Secret-gated and frequently
 * rejects clients, so the home feed is built from the public, secret-free
 * mobile search endpoint (search.kuwo.cn) which reliably returns songs. The
 * 推荐歌单 row uses synthesized "kw:<keyword>" playlists that resolve to a
 * search on tap, so the home page is never left blank.
 *
 * The loaded feed is cached in a StateFlow so switching tabs and re-entering
 * 首页 does not force a full reload; refresh only happens on pull-to-refresh,
 * on first launch, or when the kuwo login cookie changes.
 */
class HomeRepository(private val api: KuwoApi) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _data = MutableStateFlow<HomeData?>(null)
    val data: StateFlow<HomeData?> = _data.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val loadedOnce = AtomicBoolean(false)
    private val refreshInFlight = AtomicBoolean(false)

    /** Last kuwo cookie value seen (process-lifetime), used to refresh only on real change. */
    @Volatile
    private var seenCookie: String? = null

    /**
     * Called whenever the kuwo cookie is observed from the UI. Ignores the first
     * observation (initial state, handled by [start]), and only triggers a refresh
     * when the cookie VALUE actually changed (login/logout). Re-entering 首页 with
     * the same cookie is a no-op, so switching tabs never forces a reload.
     */
    fun onCookieChanged(cookie: String) {
        val s = seenCookie
        if (s == null) { seenCookie = cookie; start(); return }
        seenCookie = cookie
        if (s != cookie) refresh()
    }

    private val dailyKeywords = listOf("热门歌曲", "热搜", "新歌", "华语")
    private val guessKeywords = listOf("经典老歌", "流行", "DJ舞曲", "伤感")
    private val playlistThemes = listOf("华语流行", "经典老歌", "DJ舞曲", "英文金曲", "网络热歌", "轻音乐")

    /** First-time load only; subsequent calls are no-ops until [refresh]. */
    fun start() {
        if (loadedOnce.compareAndSet(false, true)) refresh()
    }

    fun refresh() {
        if (!refreshInFlight.compareAndSet(false, true)) return
        _refreshing.value = true
        scope.launch {
            val result = runCatching { build() }.getOrDefault(HomeData())
            _data.value = result
            _refreshing.value = false
            refreshInFlight.set(false)
        }
    }

    private suspend fun build(): HomeData {
        val daily = firstNonEmpty(dailyKeywords, 1, 12)
        val guess = firstNonEmpty(guessKeywords, 1, 12)
        return HomeData(
            dailyRecommend = daily,
            guessYouLike = guess,
            recommendPlaylists = tryPlaylists()
        )
    }

    /** Try each keyword until a non-empty result is found (guards against a dead keyword). */
    private suspend fun firstNonEmpty(keywords: List<String>, pn: Int, rn: Int): List<Song> {
        val list = keywords.shuffled()
        for (kw in list) {
            val r = api.searchSongs(kw, pn, rn)
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }

    private suspend fun tryPlaylists(): List<FavoritePlaylist> {
        val out = ArrayList<FavoritePlaylist>()
        for (t in playlistThemes.shuffled().take(6)) {
            val n = api.searchSongs(t, 1, 5).size
            out.add(FavoritePlaylist("kw:$t", "$t 精选", null, (n * 1000).toLong()))
        }
        return out
    }
}