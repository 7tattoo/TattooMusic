package com.spotify.music.ui.screens

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import coil.compose.AsyncImage
import com.spotify.music.AppContainer
import com.spotify.music.data.model.Comment
import com.spotify.music.data.model.Song
import com.spotify.music.data.model.SongSource
import androidx.media3.common.Player
import com.spotify.music.player.PlayerController
import com.spotify.music.ui.SongCover
import com.spotify.music.ui.components.LyricsView
import com.spotify.music.ui.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------- Portrait player (参考 S2.PNG) ----------------

@Composable
fun PlayerScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pc = container.playerController
    val context = LocalContext.current
    val song by pc.currentSong.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            // top bar
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface)
                }
                Text("正在播放", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreHoriz, contentDescription = "更多",
                            tint = MaterialTheme.colorScheme.onSurface)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("添加到歌单") },
                            leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, contentDescription = null) },
                            enabled = song != null,
                            onClick = { menuOpen = false; showPlaylistPicker = true }
                        )
                        DropdownMenuItem(
                            text = { Text("定时关闭") },
                            leadingIcon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
                            onClick = { menuOpen = false; showSleep = true }
                        )
                    }
                }
            }

            // cover
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                SongCover(
                    model = song?.pic,
                    modifier = Modifier
                        .width(210.dp).height(210.dp)
                        .clip(RoundedCornerShape(24.dp))
                )
            }

            Spacer(Modifier.height(16.dp))

            // title / artist
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = song?.title ?: "未在播放",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = song?.artist ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(8.dp))

            // scrolling lyrics (blue region)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LyricsView(pc, Modifier.fillMaxSize())
            }

            // progress + transport
            ProgressRow(pc)
            TransportControls(pc, Modifier.fillMaxWidth())

            Spacer(Modifier.height(10.dp))

            // comment + download icons (red region)
            BottomActionRow(pc, container, Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showPlaylistPicker) {
        PlaylistPickerSheet(container, song, onDismiss = { showPlaylistPicker = false })
    }
    if (showSleep) {
        PlayerSleepDialog(container, onDismiss = { showSleep = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistPickerSheet(
    container: AppContainer,
    song: Song?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val playlists by container.playlistRepository.selfPlaylists.collectAsState()
    var createName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
            Text("添加到歌单", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (creating) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(
                        value = createName,
                        onValueChange = { createName = it },
                        label = { Text("歌单名称") },
                        modifier = Modifier.weight(1f).height(54.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        val name = createName.trim().ifBlank { "新建歌单" }
                        val pl = container.playlistRepository.createPlaylist(name)
                        song?.let { container.playlistRepository.addToPlaylist(pl.id, it) }
                        createName = ""
                        creating = false
                        Toast.makeText(context, "已添加到「$name」", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }) { Text("创建") }
                }
            } else {
                TextButton(onClick = { creating = true }) {
                    Icon(Icons.Rounded.PlaylistAdd, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("新建歌单")
                }
            }
            Spacer(Modifier.height(6.dp))
            androidx.compose.material3.HorizontalDivider()
            if (playlists.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("暂无歌单，可点击上方「新建歌单」创建",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                    items(playlists) { pl ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                song?.let { s ->
                                    container.playlistRepository.addToPlaylist(pl.id, s)
                                    Toast.makeText(context, "已添加到「${pl.name}」", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                }
                            }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Icon(
                                Icons.Rounded.QueueMusic, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(pl.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text("${pl.songs.size} 首", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerSleepDialog(container: AppContainer, onDismiss: () -> Unit) {
    val active by container.sleepTimer.active.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定时关闭") },
        text = {
            Column {
                Text(if (active) "当前剩余 ${container.sleepTimer.remainingMinutes()} 分钟" else "在一段时间后自动停止播放。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row {
                    listOf(15L, 30L, 60L, 90L).forEach { m ->
                        TextButton(onClick = {
                            container.sleepTimer.start(m)
                            onDismiss()
                        }) { Text("${m}分钟") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                container.sleepTimer.stop()
                onDismiss()
            }) { Text(if (active) "取消定时" else "关闭") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ---------------- Landscape player (参考 3.png: 左专辑图/歌词 + 右信息控制) ----------------

private val CarPinkTop = Color(0xFFE9A8BC)
private val CarPinkBottom = Color(0xFFD27E96)

@Composable
fun LandscapePlayerScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pc = container.playerController
    val song by pc.currentSong.collectAsState()
    var showLyrics by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(
            Brush.linearGradient(
                colors = listOf(CarPinkTop, CarPinkBottom)
            )
        )
    ) {
        val landscape = maxWidth >= maxHeight
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                // ---------------- LEFT: 专辑图 (点击切换动态歌词) ----------------
                Column(
                    Modifier.weight(1.08f).fillMaxSize()
                        .padding(start = 28.dp, top = 40.dp, bottom = 36.dp)
                ) {
                    Box(
                        Modifier.fillMaxWidth().weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable { showLyrics = !showLyrics },
                        contentAlignment = Alignment.Center
                    ) {
                        if (showLyrics) {
                            LyricsView(pc, Modifier.fillMaxSize())
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                // Keep the artwork SQUARE centered in the tall left
                                // column; fitting it to min(maxWidth,maxHeight) avoids
                                // stretching the cover into a tall rectangle.
                                BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
                                    val side = minOf(maxWidth, maxHeight)
                                    SongCover(
                                        model = song?.pic,
                                        modifier = Modifier.size(side).aspectRatio(1f)
                                            .clip(RoundedCornerShape(18.dp))
                                    )
                                }
                            }
                        }
                        Text(
                            text = if (showLyrics) "点击查看专辑" else "点击查看动态歌词",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                        )
                    }
                    // 元数据行 (专辑名, 轻量)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = song?.album?.takeIf { it.isNotBlank() } ?: " ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // ---------------- RIGHT: 歌名/歌手/功能键/进度/控制 ----------------
                Column(
                    Modifier.weight(1f).fillMaxSize()
                        .padding(end = 32.dp, top = 56.dp, bottom = 44.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    // 返回
                    Box(Modifier.fillMaxWidth()) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.align(Alignment.TopStart).size(44.dp)
                        ) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = Color.White)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = song?.title ?: "未在播放",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.TopStart).padding(top = 64.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = song?.artist ?: "",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.TopStart).padding(top = 104.dp)
                        )
                    }
                    Spacer(Modifier.height(72.dp))

                    // 功能键行: 播放列表 / 喜欢 / 加入歌单
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {}) {
                            Icon(Icons.Rounded.QueueMusic, contentDescription = "播放列表", tint = Color.White)
                        }
                        Spacer(Modifier.width(6.dp))
                        IconButton(onClick = {}) {
                            Icon(Icons.Rounded.FavoriteBorder, contentDescription = "喜欢", tint = Color.White)
                        }
                        Spacer(Modifier.width(6.dp))
                        IconButton(onClick = {}) {
                            Icon(Icons.Rounded.PlaylistAdd, contentDescription = "加入歌单", tint = Color.White)
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    CarProgressRow(pc)
                    Spacer(Modifier.height(4.dp))
                    CarTransportControls(pc)
                }
            }
        } else {
            // 方屏/竖屏车载: 上下堆叠, 仍用粉紫渐变白字
            Column(Modifier.fillMaxSize().padding(28.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                    Text("正在播放", style = MaterialTheme.typography.titleMedium,
                        color = Color.White, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.12f)).clickable { showLyrics = !showLyrics }) {
                    if (showLyrics) {
                        LyricsView(pc, Modifier.fillMaxSize())
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            SongCover(model = song?.pic,
                                modifier = Modifier.fillMaxWidth(0.55f).aspectRatio(1f)
                                    .clip(RoundedCornerShape(22.dp)))
                        }
                    }
                    Text(if (showLyrics) "点击查看专辑" else "点击查看动态歌词",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(song?.title ?: "", style = MaterialTheme.typography.titleMedium, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song?.artist ?: "", style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                CarProgressRow(pc)
                CarTransportControls(pc)
            }
        }
    }
}

@Composable
private fun CarProgressRow(pc: PlayerController, modifier: Modifier = Modifier) {
    val position by pc.positionMs.collectAsState()
    val duration by pc.durationMs.collectAsState()
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    val shown = dragPosition ?: position.toFloat()
    val total = (if (duration > 0) duration else 1).toFloat()

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Slider(
            value = shown.coerceIn(0f, total),
            onValueChange = { dragPosition = it },
            onValueChangeFinished = {
                dragPosition?.let { pc.seekTo(it.toLong()) }
                dragPosition = null
            },
            valueRange = 0f..total,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            )
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(shown.toLong()), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
            Text(formatDuration(total.toLong()), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun CarTransportControls(pc: PlayerController, modifier: Modifier = Modifier) {
    val playing by pc.isPlaying.collectAsState()
    val hasPrev by pc.hasPrevious.collectAsState()
    val hasNext by pc.hasNext.collectAsState()

    Row(
        modifier = modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { pc.toggleShuffle() }) {
            Icon(Icons.Rounded.Shuffle, contentDescription = "随机",
                tint = if (pc.shuffle) Color.White else Color.White.copy(alpha = 0.5f))
        }
        IconButton(onClick = { pc.previous() }, enabled = hasPrev) {
            Icon(Icons.Rounded.SkipPrevious, contentDescription = "上一首", Modifier.size(34.dp), tint = Color.White)
        }
        Box(
            modifier = Modifier.size(68.dp).clip(RoundedCornerShape(50)).background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = { pc.togglePlayPause() }, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    tint = CarPinkBottom,
                    modifier = Modifier.size(38.dp)
                )
            }
        }
        IconButton(onClick = { pc.next() }, enabled = hasNext) {
            Icon(Icons.Rounded.SkipNext, contentDescription = "下一首", Modifier.size(34.dp), tint = Color.White)
        }
        IconButton(onClick = {
            pc.setRepeatMode(
                when (pc.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            )
        }) {
            Icon(
                if (pc.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                contentDescription = "循环",
                tint = if (pc.repeatMode != Player.REPEAT_MODE_OFF) Color.White else Color.White.copy(alpha = 0.5f)
            )
        }
    }
}

// ---------------- shared sub-components ----------------

@Composable
private fun ProgressRow(pc: PlayerController, modifier: Modifier = Modifier) {
    val position by pc.positionMs.collectAsState()
    val duration by pc.durationMs.collectAsState()
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    val shown = dragPosition ?: position.toFloat()
    val total = (if (duration > 0) duration else 1).toFloat()

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Slider(
            value = shown.coerceIn(0f, total),
            onValueChange = { dragPosition = it },
            onValueChangeFinished = {
                dragPosition?.let { pc.seekTo(it.toLong()) }
                dragPosition = null
            },
            valueRange = 0f..total
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(shown.toLong()), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatDuration(total.toLong()), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TransportControls(pc: PlayerController, modifier: Modifier = Modifier) {
    val playing by pc.isPlaying.collectAsState()
    val hasPrev by pc.hasPrevious.collectAsState()
    val hasNext by pc.hasNext.collectAsState()

    Row(
        modifier = modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { pc.setRepeatMode(if (pc.repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_OFF else if (pc.repeatMode == Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL) }) {
            Icon(
                when (pc.repeatMode) {
                    Player.REPEAT_MODE_ONE -> Icons.Rounded.RepeatOne
                    Player.REPEAT_MODE_ALL -> Icons.Rounded.Repeat
                    else -> Icons.Rounded.Repeat
                },
                contentDescription = "循环",
                tint = if (pc.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = { pc.previous() }, enabled = hasPrev) {
            Icon(Icons.Rounded.SkipPrevious, contentDescription = "上一首", Modifier.size(34.dp))
        }
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = { pc.togglePlayPause() }, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(38.dp)
                )
            }
        }
        IconButton(onClick = { pc.next() }, enabled = hasNext) {
            Icon(Icons.Rounded.SkipNext, contentDescription = "下一首", Modifier.size(34.dp))
        }
        IconButton(onClick = { pc.toggleShuffle() }) {
            Icon(Icons.Rounded.Shuffle, contentDescription = "随机",
                tint = if (pc.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BottomActionRow(pc: PlayerController, container: AppContainer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val song by pc.currentSong.collectAsState()

    var showComments by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // comment
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = { showComments = song != null && song?.source == SongSource.ONLINE }) {
                Icon(Icons.AutoMirrored.Rounded.Comment, contentDescription = "评论")
            }
            Text("评论", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // download
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = { song?.let { downloadSong(context, container, it, scope) } }) {
                Icon(Icons.Rounded.ArrowDownward, contentDescription = "下载")
            }
            Text("下载", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // favorite placeholder (always visible, toggles local new-playlist add)
        val fav = remember(song?.id) { mutableStateOf(false) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = {
                fav.value = !fav.value
                song?.let {
                    if (fav.value) {
                        val name = "我喜欢"
                        val pl = container.playlistRepository.selfPlaylists.value.firstOrNull { p -> p.name == name }
                            ?: container.playlistRepository.createPlaylist(name)
                        container.playlistRepository.addToPlaylist(pl.id, it)
                        Toast.makeText(context, "已收藏到「我喜欢」", Toast.LENGTH_SHORT).show()
                    }
                }
            }) {
                Icon(
                    imageVector = if (fav.value) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "喜欢",
                    tint = if (fav.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("喜欢", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showComments) {
        CommentsSheet(container = container, song = song) { showComments = false }
    }
}

// ---------------- Comments sheet ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentsSheet(container: AppContainer, song: Song?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var comments by remember(song?.id) { mutableStateOf<List<Comment>>(emptyList()) }
    var loading by remember(song?.id) { mutableStateOf(true) }

    LaunchedEffect(song?.id) {
        loading = true
        comments = withContext(Dispatchers.IO) {
            song?.let { container.api.comments(it.id, 1, 30) } ?: emptyList()
        }
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("歌曲评论", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp).align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(8.dp))
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                }
            } else if (comments.isEmpty()) {
                Text("暂无评论", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(32.dp).align(Alignment.CenterHorizontally))
            } else {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                    items(comments.size) { i ->
                        val c = comments[i]
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                            SongCover(model = c.avatarUrl, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(c.user, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(c.dateText, style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(c.content, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

// ---------------- download helper ----------------

private fun downloadSong(
    context: android.content.Context,
    container: AppContainer,
    song: Song,
    scope: kotlinx.coroutines.CoroutineScope
) {
    scope.launch {
        Toast.makeText(context, "正在下载…", Toast.LENGTH_SHORT).show()
        val ok = withContext(Dispatchers.IO) {
            try {
                val url = when (song.source) {
                    SongSource.LOCAL -> song.localPath ?: return@withContext false
                    SongSource.ONLINE -> container.api.getPlayUrl(song.id) ?: return@withContext false
                }
                val bytes = container.api.rawBytes(url) ?: return@withContext false
                val fileName = "${song.title} - ${song.artist}.${extFrom(url)}"
                writeToMediaStore(context, fileName, bytes)
            } catch (e: Exception) {
                false
            }
        }
        if (ok) {
            Toast.makeText(context, "下载完成", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "下载失败", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun extFrom(url: String): String {
    val clean = url.substringBefore("?").substringBefore("#")
    val ext = clean.substringAfterLast('.', "")
    return if (ext.length in 2..4) ext else "mp3"
}

private fun writeToMediaStore(
    context: android.content.Context,
    fileName: String,
    bytes: ByteArray
): Boolean {
    return try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/TattooMusic")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val uri: Uri = resolver.insert(collection, values) ?: return false
        resolver.openOutputStream(uri)?.use { out -> out.write(bytes) } ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    } catch (e: Exception) {
        false
    }
}