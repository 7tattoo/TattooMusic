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
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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

    val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch { container.localMusicRepository.scan() }
        } else {
            Toast.makeText(context, "需要存储/音频权限才能扫描本地音乐", Toast.LENGTH_LONG).show()
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
            if (hasAudioPermission()) {
                scope.launch { container.localMusicRepository.scan() }
            }
        }
    }

    if (songs.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            EmptyState(Icons.Rounded.MusicOff, "还没有本地音乐", "扫描你的音乐目录以发现本地歌曲")
            Spacer(Modifier.height(16.dp))
            if (isScanning) {
                CircularProgressIndicator()
            } else {
                Button(onClick = {
                    if (hasAudioPermission()) scope.launch { container.localMusicRepository.scan() }
                    else permissionLauncher.launch(audioPermission)
                }) {
                    androidx.compose.material3.Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("扫描音乐")
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { dirPicker.launch(null) }) {
                    androidx.compose.material3.Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("选择音乐目录")
                }
                container.settings.musicRoot.collectAsState().value?.let { root ->
                    Spacer(Modifier.height(10.dp))
                    Text("当前目录：$root", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    } else {
        Column(modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("本地音乐", style = MaterialTheme.typography.titleMedium)
                    Text("${songs.size} 首", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = {
                    if (hasAudioPermission()) scope.launch { container.localMusicRepository.scan() }
                    else permissionLauncher.launch(audioPermission)
                }) {
                    if (isScanning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("重新扫描")
                }
            }
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