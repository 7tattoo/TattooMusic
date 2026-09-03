package com.spotify.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotify.music.AppContainer
import com.spotify.music.data.model.FavoritePlaylist
import com.spotify.music.data.model.Song
import com.spotify.music.data.repo.HomeData
import com.spotify.music.ui.CenterLoading
import com.spotify.music.ui.SectionHeader
import com.spotify.music.ui.SongCover
import com.spotify.music.ui.SongRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    var data by remember { mutableStateOf<HomeData?>(null) }

    LaunchedEffect(Unit) {
        data = container.homeRepository.load()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("刺青音乐", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = {
                        // sample search: play first daily hit so the feature is discoverable
                        data?.dailyRecommend?.firstOrNull()?.let { song ->
                            container.playerController.playQueue(listOf(song), 0)
                            onOpenPlayer()
                        }
                    }) {
                        Icon(Icons.Rounded.Search, contentDescription = "搜索")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        val home = data
        if (home == null) {
            Box(Modifier.padding(padding).fillMaxSize()) { CenterLoading() }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)
            ) {
                item { SectionHeader("每日推荐", trailing = "点击播放") }
                item {
                    SongStrip(home.dailyRecommend, container, onOpenPlayer)
                }
                item { Spacer(Modifier.height(8.dp)) }
                item { SectionHeader("猜你喜欢") }
                item {
                    SongStrip(home.guessYouLike, container, onOpenPlayer)
                }
                item { Spacer(Modifier.height(8.dp)) }
                item { SectionHeader("推荐歌单") }
                items(home.recommendPlaylists.chunked(2)) { rowItems ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        rowItems.forEach { pl ->
                            Box(Modifier.weight(1f)) {
                                PlaylistTile(pl, container, onOpenPlayer)
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    }
}

/** Horizontal scroll of song covers. */
@Composable
private fun SongStrip(songs: List<Song>, container: AppContainer, onOpenPlayer: () -> Unit) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(songs.take(12)) { song ->
            Column(
                Modifier.width(108.dp).clickable {
                    container.playerController.playQueue(songs, songs.indexOf(song))
                    onOpenPlayer()
                }
            ) {
                SongCover(model = song.pic, modifier = Modifier.size(108.dp))
                Spacer(Modifier.height(6.dp))
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** A recommended playlist tile; click opens its songs. */
@Composable
private fun PlaylistTile(pl: FavoritePlaylist, container: AppContainer, onOpenPlayer: () -> Unit) {
    var show by remember(pl.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { show = true }) {
        SongCover(model = pl.picUrl, modifier = Modifier.fillMaxWidth().height(150.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            text = pl.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (show) {
        PlaylistSongsSheet(pl, container, onOpenPlayer) { show = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistSongsSheet(
    pl: FavoritePlaylist,
    container: AppContainer,
    onOpenPlayer: () -> Unit,
    onDismiss: () -> Unit
) {
    var songs by remember(pl.id) { mutableStateOf<List<Song>?>(null) }
    LaunchedEffect(pl.id) {
        songs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            container.api.playlistSongs(pl.id)
        }
    }
    val favorited = container.playlistRepository.favorites.collectAsState().value.any { it.id == pl.id }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pl.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Row(Modifier.clickable {
                    if (favorited) container.playlistRepository.removeFavorite(pl.id)
                    else container.playlistRepository.addFavorite(pl)
                }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (favorited) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = "收藏",
                        tint = if (favorited) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (favorited) "已收藏" else "收藏", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("共 ${songs?.size ?: 0} 首", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            if (songs == null) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                    items(songs!!) { song ->
                        SongRow(song, onClick = {
                            container.playerController.playQueue(songs!!, songs!!.indexOf(song))
                            onOpenPlayer()
                            onDismiss()
                        })
                    }
                }
            }
        }
    }
}