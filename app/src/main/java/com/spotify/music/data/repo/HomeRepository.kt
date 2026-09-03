package com.spotify.music.data.repo

import com.spotify.music.data.api.KuwoApi
import com.spotify.music.data.model.FavoritePlaylist
import com.spotify.music.data.model.Song

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
 */
class HomeRepository(private val api: KuwoApi) {

    private val dailyKeywords = listOf("热门歌曲", "热搜", "新歌", "华语")
    private val guessKeywords = listOf("经典老歌", "流行", "DJ舞曲", "伤感")
    private val playlistThemes = listOf("华语流行", "经典老歌", "DJ舞曲", "英文金曲", "网络热歌", "轻音乐")

    suspend fun load(): HomeData = runCatching {
        val daily = firstNonEmpty(dailyKeywords, 1, 12)
        val guess = firstNonEmpty(guessKeywords, 1, 12)
        HomeData(
            dailyRecommend = daily,
            guessYouLike = guess,
            recommendPlaylists = tryPlaylists()
        )
    }.getOrDefault(HomeData())

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