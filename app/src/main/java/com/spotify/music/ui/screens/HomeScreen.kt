package com.spotify.music.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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
    val homeRepo = container.homeRepository
    val data by homeRepo.data.collectAsState()
    val refreshing by homeRepo.refreshing.collectAsState()

    // Cache the feed across tab switches: initial load once, then only refresh on
    // login/logout cookie change or pull-to-refresh. Returning to 首页 no longer reloads.
    val cookie by container.settings.kuwoCookie.collectAsState()
    LaunchedEffect(cookie) {
        homeRepo.onCookieChanged(cookie ?: "")
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
            PullRefreshFrame(
                isRefreshing = refreshing,
                onRefresh = { homeRepo.refresh() },
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
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
}

/** A minimal pull-to-refresh container (Material3 1.2.1 has no built-in PullToRefreshBox). */
@Composable
private fun PullRefreshFrame(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val pull = remember { Animatable(0f) }
    val thresholdPx = with(density) { 84.dp.toPx() }
    val maxPx = with(density) { 140.dp.toPx() }

    val currentRefreshing by rememberUpdatedState(isRefreshing)
    val currentOnRefresh by rememberUpdatedState(onRefresh)

    // Settle back once the refresh finishes.
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) pull.snapTo(0f)
    }

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.Drag && available.y > 0 && !currentRefreshing) {
                    val next = (pull.value + available.y).coerceIn(0f, maxPx)
                    if (next != pull.value) {
                        scope.launch { pull.snapTo(next) }
                        return Offset(0f, available.y)
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull.value >= thresholdPx && !currentRefreshing) {
                    pull.snapTo(thresholdPx)
                    currentOnRefresh()
                } else {
                    pull.animateTo(0f)
                }
                return Velocity.Zero
            }
        }
    }

    Box(modifier = modifier.nestedScroll(connection)) {
        content()
        val shown = pull.value > 0f || currentRefreshing
        if (shown) {
            val progress = (pull.value / thresholdPx).coerceIn(0f, 1f)
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .size(26.dp)
                    .graphicsLayer {
                        alpha = if (currentRefreshing) 1f else progress
                    },
                strokeWidth = 3.dp
            )
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