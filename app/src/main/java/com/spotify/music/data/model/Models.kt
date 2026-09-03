package com.spotify.music.data.model

import kotlinx.serialization.Serializable

/** A single playable song, either online (kuwo) or local. */
@Serializable
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val pic: String? = null,
    val durationMs: Long = 0,
    val source: SongSource = SongSource.ONLINE,
    val localPath: String? = null,
    val lyricPath: String? = null,
    val isFavorite: Boolean = false
)

@Serializable
enum class SongSource { ONLINE, LOCAL }

/** A user-created or imported playlist that holds songs locally. */
@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val createdAt: Long,
    val songs: List<Song> = emptyList()
)

/** A favorite (kong) playlist fetched from the network. */
@Serializable
data class FavoritePlaylist(
    val id: String,
    val name: String,
    val picUrl: String? = null,
    val playCount: Long = 0
)

/** A synchronized lyric line. */
data class LyricLine(
    val timeMs: Long,
    val text: String
)

/** A music comment. */
data class Comment(
    val user: String,
    val avatarUrl: String? = null,
    val content: String,
    val dateText: String,
    val likeCount: Int,
    val id: String
)

/** A music account profile (local only for now). */
data class UserProfile(
    val nickname: String = "刺青用户",
    val signature: String = "让每一首歌都值得被听见",
    val avatarUrl: String? = null
)