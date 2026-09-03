package com.spotify.music.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotify.music.AppContainer

@Composable
fun SettingsScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val usb by container.usbController.status.collectAsState()
    val carLyrics by container.settings.carLyricsEnabled.collectAsState()
    val usbExclusive by container.settings.usbExclusiveEnabled.collectAsState()

    var showSleep by remember { mutableStateOf(false) }
    var showDirFilter by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    LazyColumn(modifier.fillMaxSize()) {
        item { Section("睡眠定时器", Icons.Rounded.Schedule, subtitle = sleepSubtitle(container)) { showSleep = true } }
        item { Section("目录过滤", Icons.Rounded.Lyrics, subtitle = "过滤本地扫描时忽略的音乐目录") { showDirFilter = true } }

        item {
            SettingToggle(
                title = "独占USB输出",
                subtitle = if (usbExclusive && usb.connected) {
                    "${usb.deviceName} · ${usb.sampleRate}Hz"
                } else if (usbExclusive) {
                    "已开启，等待连接 USB DAC ($usb.deviceName)"
                } else {
                    "未开启（$usb.deviceName）"
                },
                icon = Icons.Rounded.Usb,
                checked = usbExclusive,
                onToggle = { container.settings.setUsbExclusive(it) }
            )
        }

        item {
            SettingToggle(
                title = "Joviincar车载歌词",
                subtitle = "在 vivo 智能车载主页显示滚动歌词",
                icon = Icons.Rounded.Timer,
                checked = carLyrics,
                onToggle = { container.settings.setCarLyrics(it) }
            )
        }

        item { Section("关于软件", Icons.Rounded.Info, subtitle = "刺青音乐 Tattoo Music v1.0.0") { showAbout = true } }
    }

    if (showSleep) SleepTimerDialog(container) { showSleep = false }
    if (showDirFilter) DirFilterDialog(container) { showDirFilter = false }
    if (showAbout) AboutDialog { showAbout = false }
}

private fun sleepSubtitle(container: AppContainer): String {
    val timer = container.sleepTimer
    return if (timer.active.value) "剩余 ${timer.remainingMinutes()} 分钟" else "未设置，定时停止播放"
}

@Composable
private fun Section(title: String, icon: ImageVector, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
}

@Composable
private fun SettingToggle(title: String, subtitle: String, icon: ImageVector, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
}

// ---------------- dialogs ----------------

@Composable
private fun SleepTimerDialog(container: AppContainer, onDismiss: () -> Unit) {
    val active by container.sleepTimer.active.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("睡眠定时器") },
        text = {
            Column {
                Text("在一段时间后自动停止播放。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                val options = listOf(15L, 30L, 45L, 60L, 90L)
                Row {
                    options.forEach { m ->
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

@Composable
private fun DirFilterDialog(container: AppContainer, onDismiss: () -> Unit) {
    val ignored by container.settings.ignoredDirs.collectAsState()
    var input by remember { mutableStateOf("") }

    fun add() {
        if (input.isNotBlank()) {
            container.settings.toggleIgnoreDir(input.trim(), true)
            input = ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("目录过滤") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("被忽略的目录不会在本地扫描中显示。可输入目录路径后添加。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(value = input, onValueChange = { input = it },
                        label = { Text("目录路径") }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { add() }) { Text("添加") }
                }
                Spacer(Modifier.height(6.dp))
                if (ignored.isEmpty()) {
                    Text("暂无过滤目录", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ignored.forEach { dir ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(dir, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { container.settings.toggleIgnoreDir(dir, false) }) {
                            Icon(Icons.Rounded.Close, contentDescription = "移除", Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { add(); onDismiss() }) { Text("完成") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于软件") },
        text = {
            Column {
                Text("刺青音乐 (Tattoo Music)", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("版本 1.0.0 · arm64-v8a", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Text("一个基于酷我音乐开放 API 的在线/本地音乐播放器。支持在线歌曲、滚动歌词、本地音乐扫描、USB DAC 独占输出以及 vivo 智能车载滚动歌词。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}