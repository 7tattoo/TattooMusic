package com.spotify.music.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.spotify.music.AppContainer
import com.spotify.music.ui.EmptyState
import com.spotify.music.ui.SongRow
import kotlinx.coroutines.launch

@Composable
fun LocalScreen(
    container: AppContainer,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val songs by container.localMusicRepository.songs.collectAsState()
    val isScanning by container.localMusicRepository.isScanning.collectAsState()
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var showDirs by remember { mutableStateOf(false) }

    val audioPermission = container.localMusicRepository.requiredAudioPermission()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch { container.localMusicRepository.scan() }
        } else {
            Toast.makeText(context, "需要存储/音频权限才能扫描本地音乐", Toast.LENGTH_LONG).show()
        }
    }

    // Returning from the "all files access" settings page -> retry scan if granted.
    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (container.localMusicRepository.canScanByFileSystem()) {
            scope.launch { container.localMusicRepository.scan() }
        }
    }

    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val path = treeUriToPath(uri)
            if (path != null) {
                container.settings.addMusicRoot(path)
                Toast.makeText(context, "已添加音乐目录 $path", Toast.LENGTH_LONG).show()
                if (container.localMusicRepository.canScanByFileSystem()) {
                    scope.launch { container.localMusicRepository.scan() }
                }
            }
        }
    }

    fun openStorageSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        }
        allFilesLauncher.launch(intent)
    }

    fun performScan() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager() ->
                { openStorageSettings(); Toast.makeText(context, "请在系统设置中开启「所有文件访问」", Toast.LENGTH_LONG).show() }
            !container.localMusicRepository.canScanByFileSystem() ->
                permissionLauncher.launch(audioPermission)
            else ->
                scope.launch { container.localMusicRepository.scan() }
        }
    }

    Column(modifier.fillMaxSize()) {
        // top bar: title + overflow "..." menu
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.padding(start = 8.dp)) {
                Text("本地音乐", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (isScanning) "正在扫描…" else "${songs.size} 首",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (isScanning) "扫描中…" else "扫描音乐") },
                        leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                        enabled = !isScanning,
                        onClick = { menuOpen = false; performScan() }
                    )
                    DropdownMenuItem(
                        text = { Text("添加音乐目录") },
                        leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                        onClick = { menuOpen = false; dirPicker.launch(null) }
                    )
                    DropdownMenuItem(
                        text = { Text("管理音乐目录") },
                        leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                        onClick = { menuOpen = false; showDirs = true }
                    )
                    DropdownMenuItem(
                        text = { Text("管理文件权限") },
                        leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                        onClick = { menuOpen = false; openStorageSettings() }
                    )
                }
            }
        }

        if (songs.isEmpty()) {
            val roots by container.settings.musicRoots.collectAsState()
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.padding(24.dp).align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    EmptyState(Icons.Rounded.MusicOff, "还没有本地音乐", "点击右上角「⋮」扫描音乐或添加音乐目录")
                    if (roots.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "已添加 ${roots.size} 个目录，可再添加更多",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(songs) { song ->
                    SongRow(song, onClick = {
                        container.playerController.playQueue(songs, songs.indexOf(song))
                        onOpenPlayer()
                    })
                }
            }
        }
    }

    if (showDirs) {
        MusicDirDialog(container) { showDirs = false }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MusicDirDialog(container: AppContainer, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val roots by container.settings.musicRoots.collectAsState()
    val scope = rememberCoroutineScope()
    val addDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val path = treeUriToPath(uri)
            if (path != null) {
                container.settings.addMusicRoot(path)
                if (container.localMusicRepository.canScanByFileSystem()) {
                    scope.launch { container.localMusicRepository.scan() }
                }
            }
        }
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
            Text("音乐目录（可多个）", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("总目录下所有音频都会被扫描，可添加多个目录同时管理",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.HorizontalDivider()
            if (roots.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("尚未添加目录，扫描将覆盖整个存储", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                roots.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        androidx.compose.material3.TextButton(onClick = {
                            container.settings.removeMusicRoot(r)
                            if (container.localMusicRepository.canScanByFileSystem()) {
                                scope.launch { container.localMusicRepository.scan() }
                            }
                        }) { Text("移除") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedButton(onClick = { addDir.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("添加目录")
            }
        }
    }
}

/** Best-effort convert a SAF document tree uri to a real directory path. */
private fun treeUriToPath(uri: Uri): String? {
    val docId = uri.lastPathSegment?.removePrefix("tree/") ?: return null
    val decoded = android.net.Uri.decode(docId)
    if (decoded.startsWith("primary:")) {
        return "${Environment.getExternalStorageDirectory().absolutePath}/${decoded.removePrefix("primary:")}".trimEnd('/')
    }
    // e.g. "1F0A-1234:Music" -> /storage/1F0A-1234/Music
    return "/storage/${decoded.replace(":", "/")}".trimEnd('/')
}