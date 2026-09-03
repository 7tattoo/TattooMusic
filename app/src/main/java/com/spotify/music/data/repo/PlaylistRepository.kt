package com.spotify.music.data.repo

import android.content.Context
import android.content.SharedPreferences
import com.spotify.music.data.model.FavoritePlaylist
import com.spotify.music.data.model.LocalPlaylist
import com.spotify.music.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Playlist & history repository. Persists self-built playlists, favorite
 * playlists and the recent-play queue as JSON in SharedPreferences (local-first,
 * no backend dependency).
 */
class PlaylistRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("tattoo_playlists", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _selfPlaylists = MutableStateFlow<List<LocalPlaylist>>(loadSelf())
    val selfPlaylists: StateFlow<List<LocalPlaylist>> = _selfPlaylists.asStateFlow()

    private val _favorites = MutableStateFlow<List<FavoritePlaylist>>(loadFavorites())
    val favorites: StateFlow<List<FavoritePlaylist>> = _favorites.asStateFlow()

    private val _recent = MutableStateFlow<List<Song>>(loadRecent())
    val recent: StateFlow<List<Song>> = _recent.asStateFlow()

    // ---- self playlists ----
    fun createPlaylist(name: String): LocalPlaylist {
        val p = LocalPlaylist(
            id = System.currentTimeMillis().toString(),
            name = name.ifBlank { "新建歌单" },
            createdAt = System.currentTimeMillis()
        )
        _selfPlaylists.value = _selfPlaylists.value + p
        persistSelf()
        return p
    }

    fun addToPlaylist(playlistId: String, song: Song) {
        _selfPlaylists.value = _selfPlaylists.value.map { p ->
            if (p.id == playlistId && p.songs.none { it.id == song.id }) {
                p.copy(songs = p.songs + song)
            } else p
        }
        persistSelf()
    }

    fun removeFromPlaylist(playlistId: String, songId: String) {
        _selfPlaylists.value = _selfPlaylists.value.map { p ->
            if (p.id == playlistId) p.copy(songs = p.songs.filterNot { it.id == songId }) else p
        }
        persistSelf()
    }

    fun deletePlaylist(playlistId: String) {
        _selfPlaylists.value = _selfPlaylists.value.filterNot { it.id == playlistId }
        persistSelf()
    }

    // ---- favorite playlists ----
    fun addFavorite(p: FavoritePlaylist) {
        if (_favorites.value.any { it.id == p.id }) return
        _favorites.value = _favorites.value + p
        persistFavorites()
    }

    fun removeFavorite(id: String) {
        _favorites.value = _favorites.value.filterNot { it.id == id }
        persistFavorites()
    }

    fun clearFavorites() {
        _favorites.value = emptyList()
        persistFavorites()
    }

    // ---- recent ----
    fun addRecent(song: Song) {
        val rest = _recent.value.filterNot { it.id == song.id }
        _recent.value = (listOf(song) + rest).take(100)
        persistRecent()
    }

    fun clearRecent() {
        _recent.value = emptyList()
        persistRecent()
    }

    // ---- persistence ----
    private fun loadSelf(): List<LocalPlaylist> =
        prefs.getString("self_playlists", null)?.let { runCatching { json.decodeFromString<List<LocalPlaylist>>(it) }.getOrNull() } ?: emptyList()

    private fun loadFavorites(): List<FavoritePlaylist> =
        prefs.getString("favorites", null)?.let { runCatching { json.decodeFromString<List<FavoritePlaylist>>(it) }.getOrNull() } ?: emptyList()

    private fun loadRecent(): List<Song> =
        prefs.getString("recent", null)?.let { runCatching { json.decodeFromString<List<Song>>(it) }.getOrNull() } ?: emptyList()

    private fun persistSelf() {
        prefs.edit().putString("self_playlists", runCatching { json.encodeToString(_selfPlaylists.value) }.getOrDefault("[]")).apply()
    }

    private fun persistFavorites() {
        prefs.edit().putString("favorites", runCatching { json.encodeToString(_favorites.value) }.getOrDefault("[]")).apply()
    }

    private fun persistRecent() {
        prefs.edit().putString("recent", runCatching { json.encodeToString(_recent.value) }.getOrDefault("[]")).apply()
    }
}