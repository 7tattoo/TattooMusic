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
            container.settings.setMusicRoot(path)
            if (container.localMusicRepository.canScanByFileSystem()) {
                scope.launch { container.localMusicRepository.scan() }
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
                        text = { Text("选择音乐目录") },
                        leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                        onClick = { menuOpen = false; dirPicker.launch(null) }
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
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.padding(24.dp).align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    EmptyState(Icons.Rounded.MusicOff, "还没有本地音乐", "点击右上角「⋮」扫描音乐或选择音乐目录")
                    container.settings.musicRoot.collectAsState().value?.let { root ->
                        Spacer(Modifier.height(10.dp))
                        Text("当前目录：$root", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp))
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