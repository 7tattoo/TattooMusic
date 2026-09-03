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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
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
import com.spotify.music.data.model.FavoritePlaylist
import com.spotify.music.ui.SectionHeader
import com.spotify.music.ui.SongRow

@Composable
fun MineScreen(
    container: AppContainer,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selfPlaylists by container.playlistRepository.selfPlaylists.collectAsState()
    val favorites by container.playlistRepository.favorites.collectAsState()
    val recent by container.playlistRepository.recent.collectAsState()

    var selfExpanded by remember { mutableStateOf(false) }
    var favExpanded by remember { mutableStateOf(false) }
    var showNewDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showLoginDialog by remember { mutableStateOf(false) }
    var showAccountSheet by remember { mutableStateOf(false) }
    var activeSelfPlaylist by remember { mutableStateOf<LocalPlaylist?>(null) }
    var activeFavPlaylist by remember { mutableStateOf<FavoritePlaylist?>(null) }

    val context = LocalContext.current

    val loggedIn by container.settings.kuwoCookie.collectAsState()
    val nickname by container.settings.kuwoNickname.collectAsState()
    val isLoggedIn = !loggedIn.isNullOrBlank()
    val displayName = nickname ?: "刺青用户"

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)
    ) {
        // top 1/5 account header
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
                    .padding(24.dp)
                    .clickable { if (isLoggedIn) showAccountSheet = true else showLoginDialog = true },
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
                    Column {
                        Text(displayName, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (isLoggedIn) "已登录酷我账号 · 点击查看" else "点击登录酷我账号",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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

        // 收藏歌单 (expandable)
        item {
            ExpandableItem(
                title = "收藏歌单",
                count = favorites.size,
                icon = Icons.Rounded.Favorite,
                expanded = favExpanded,
                onToggle = { favExpanded = !favExpanded }
            )
        }
        if (favExpanded) {
            if (favorites.isEmpty()) {
                item {
                    Text("暂无收藏歌单，可在首页推荐歌单中点击「收藏」添加",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 40.dp).padding(end = 16.dp).padding(vertical = 8.dp))
                }
            }
            favorites.forEach { pl ->
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { activeFavPlaylist = pl }
                            .padding(start = 40.dp).padding(end = 16.dp).padding(top = 4.dp).padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Favorite, contentDescription = null, modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(pl.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        item { HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)) }

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
    if (showLoginDialog) {
        KuwoLoginDialog(container) { showLoginDialog = false }
    }
    if (showAccountSheet) {
        KuwoAccountSheet(container) { showAccountSheet = false }
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
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(pl.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                androidx.compose.material3.IconButton(onClick = { container.playlistRepository.deletePlaylist(pl.id); onDismiss() }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "删除歌单")
                }
            }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().height(400.dp)) {
                if (pl.songs.isEmpty()) {
                    item { Text("歌单为空", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp)) }
                }
                items(pl.songs) { song ->
                    SongRow(song, onClick = {
                        container.playerController.playQueue(pl.songs, pl.songs.indexOf(song))
                        onOpenPlayer(); onDismiss()
                    })
                }
            }
        }
    }
}

@Composable
private fun KuwoLoginDialog(container: AppContainer, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var cookie by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("登录酷我账号") },
        text = {
            Column {
                TextButton(onClick = {
                    context.startActivity(android.content.Intent(context, com.spotify.music.ui.KuwoLoginActivity::class.java))
                    onDismiss()
                }) {
                    Text("🌐 使用内置浏览器直接登录（免复制 Cookie）")
                }
                androidx.compose.material3.HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                Text("或粘贴网页端 Cookie：在电脑浏览器打开 https://www.kuwo.cn 并登录，按下 F12 → 应用/Application → Cookies，复制全部 Cookie 值粘贴到下面（保留 Hm_Iuvt_ 开头的令牌）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = cookie,
                    onValueChange = { cookie = it },
                    label = { Text("Cookie") },
                    modifier = Modifier.fillMaxWidth().height(120.dp)
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("昵称（可选）") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (cookie.isBlank()) {
                    Toast.makeText(context, "请先粘贴 Cookie", Toast.LENGTH_SHORT).show()
                } else {
                    container.settings.setKuwoAccount(cookie, nickname)
                    container.api.setAccountCookie(cookie)
                    Toast.makeText(context, "已登录酷我账号", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            }) { Text("登录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun KuwoAccountSheet(container: AppContainer, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val nickname by container.settings.kuwoNickname.collectAsState()
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(nickname ?: "刺青用户", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text("已使用酷我账号 Cookie 在线登录", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = {
                container.settings.clearKuwoAccount()
                container.api.setAccountCookie(null)
                Toast.makeText(context, "已退出登录", Toast.LENGTH_SHORT).show()
                onDismiss()
            }) {
                Text("退出登录")
            }
        }
    }
}