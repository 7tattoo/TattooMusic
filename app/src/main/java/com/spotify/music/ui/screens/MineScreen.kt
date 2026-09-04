package com.spotify.music.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotify.music.AppContainer
import com.spotify.music.data.model.LocalPlaylist
import com.spotify.music.ui.SectionHeader
import com.spotify.music.ui.SongCover
import com.spotify.music.ui.SongRow

@Composable
fun MineScreen(
    container: AppContainer,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selfPlaylists by container.playlistRepository.selfPlaylists.collectAsState()
    val recent by container.playlistRepository.recent.collectAsState()

    var selfExpanded by remember { mutableStateOf(false) }
    var showNewDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var activeSelfPlaylist by remember { mutableStateOf<LocalPlaylist?>(null) }

    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)
    ) {
        // top header
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
                    .padding(24.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(66.dp).background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(16.dp))
                    Text("刺青音乐用户", style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        // 歌单 section
        item { SectionHeader("歌单") }

        // 自建歌单 (expandable)
        item {
            ExpandableItem(
                title = "自建歌单",
                count = selfPlaylists.size,
                icon = Icons.Rounded.QueueMusic,
                expanded = selfExpanded,
                onToggle = { selfExpanded = !selfExpanded }
            )
        }
        if (selfExpanded) {
            selfPlaylists.forEach { pl ->
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { activeSelfPlaylist = pl }
                            .padding(start = 40.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.LibraryMusic, contentDescription = null, modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(pl.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${pl.songs.size}首", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 40.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                    SecondaryAction(Icons.Rounded.Add, "新建歌单") { showNewDialog = true }
                    Spacer(Modifier.width(16.dp))
                    SecondaryAction(Icons.Rounded.FileUpload, "导入歌单") { showImportDialog = true }
                }
            }
        }

        // 最近播放
        item { SectionHeader("最近播放") }
        if (recent.isEmpty()) {
            item {
                Text("还没有播放记录", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        } else {
            items(recent) { song ->
                SongRow(song, onClick = {
                    container.playerController.playQueue(recent, recent.indexOf(song))
                    onOpenPlayer()
                })
            }
        }
    }

    if (showNewDialog) {
        NewPlaylistDialog(container) { showNewDialog = false }
    }
    if (showImportDialog) {
        ImportPlaylistDialog(container, context) { showImportDialog = false }
    }
    activeSelfPlaylist?.let { pl ->
        ModalSelfPlaylistSheet(pl, container, onOpenPlayer) { activeSelfPlaylist = null }
    }
}

@Composable
private fun ExpandableItem(title: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(if (count > 0) "$count" else "", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
    }
}

@Composable
private fun SecondaryAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun NewPlaylistDialog(container: AppContainer, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建歌单") },
        text = {
            TextField(value = name, onValueChange = { name = it }, label = { Text("歌单名称") })
        },
        confirmButton = {
            TextButton(onClick = {
                container.playlistRepository.createPlaylist(name)
                onDismiss()
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ImportPlaylistDialog(container: AppContainer, context: android.content.Context, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入歌单") },
        text = { Text("将当前扫描到的本地音乐导入为一个新歌单。若尚未扫描本地音乐，请先到「本地」页面扫描。") },
        confirmButton = {
            TextButton(onClick = {
                val name = "导入的本地音乐"
                val pl = container.playlistRepository.createPlaylist(name)
                container.localMusicRepository.songs.value.forEach { container.playlistRepository.addToPlaylist(pl.id, it) }
                Toast.makeText(context, "已导入到「$name」", Toast.LENGTH_SHORT).show()
                onDismiss()
            }) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ModalSelfPlaylistSheet(pl: LocalPlaylist, container: AppContainer, onOpenPlayer: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Resolve the LIVE playlist by id so additions/removals reflect immediately even
    // though the caller captured a snapshot LocalPlaylist when it opened.
    val playlists by container.playlistRepository.selfPlaylists.collectAsState()
    val live = playlists.firstOrNull { it.id == pl.id } ?: pl
    var showAddSongs by remember { mutableStateOf(false) }

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(live.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { showAddSongs = true }) { Text("添加本地音乐") }
                androidx.compose.material3.IconButton(onClick = {
                    container.playlistRepository.deletePlaylist(live.id); onDismiss()
                }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "删除歌单")
                }
            }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().height(400.dp)) {
                if (live.songs.isEmpty()) {
                    item { Text("歌单为空", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp)) }
                }
                items(live.songs) { song ->
                    SongRow(
                        song = song,
                        trailing = {
                            androidx.compose.material3.IconButton(onClick = {
                                container.playlistRepository.removeFromPlaylist(live.id, song.id)
                                Toast.makeText(context, "已移出歌单", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "移出歌单", Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = {
                            container.playerController.playQueue(live.songs, live.songs.indexOf(song))
                            onOpenPlayer(); onDismiss()
                        }
                    )
                }
            }
        }
    }

    if (showAddSongs) {
        AddLocalSongsDialog(pl = live, container = container) { showAddSongs = false }
    }
}

@Composable
private fun AddLocalSongsDialog(pl: LocalPlaylist, container: AppContainer, onDismiss: () -> Unit) {
    val localSongs by container.localMusicRepository.visibleSongs.collectAsState()
    val playlists by container.playlistRepository.selfPlaylists.collectAsState()
    val current = playlists.firstOrNull { it.id == pl.id }?.songs.orEmpty()
    val inSet = remember(current) { current.map { it.id }.toSet() }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加到「${pl.name}」") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("点选要加入歌单的本地歌曲：", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                if (localSongs.isEmpty()) {
                    Text("暂无本地音乐，请先在「音乐」页扫描后再导入。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().height(340.dp)) {
                        items(localSongs) { s ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (s.id !in inSet) {
                                        container.playlistRepository.addToPlaylist(pl.id, s)
                                        Toast.makeText(context, "已添加「${s.title}」", Toast.LENGTH_SHORT).show()
                                    }
                                }.padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SongCover(model = s.pic, modifier = Modifier.size(42.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(s.artist, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(
                                    if (s.id in inSet) Icons.Rounded.Check else Icons.Rounded.Add,
                                    contentDescription = null,
                                    tint = if (s.id in inSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}