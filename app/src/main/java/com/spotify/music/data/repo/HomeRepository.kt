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
 * Builds the 首页 data (每日推荐 / 猜你喜欢 / 推荐歌单) from kuwo endpoints.
 * Falls back gracefully when a source is empty.
 */
class HomeRepository(private val api: KuwoApi) {

    suspend fun load(): HomeData = runCatching {
        val playlists = safePlaylists()
        val bangIds = safeBangIds()
        val daily = bangIds.getOrNull(0)?.let { api.rankMusic(it, 1, 20) }
            ?: api.searchSongs("华语", 1, 20)
        val guess = bangIds.getOrNull(1)?.let { api.rankMusic(it, 1, 20) }
            ?: api.searchSongs("经典", 1, 20)
        HomeData(daily, guess, playlists)
    }.getOrDefault(HomeData())

    private suspend fun safePlaylists(): List<FavoritePlaylist> =
        runCatching { api.recPlaylists(1, 12) }.getOrDefault(emptyList())

    private suspend fun safeBangIds(): List<String> =
        runCatching { api.recBangList().map { it.id } }.getOrDefault(emptyList())
}