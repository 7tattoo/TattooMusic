package com.spotify.music.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.spotify.music.App
import com.spotify.music.AppContainer
import com.spotify.music.ui.components.PlayerBar
import com.spotify.music.ui.screens.HomeScreen
import com.spotify.music.ui.screens.LandscapePlayerScreen
import com.spotify.music.ui.screens.LocalScreen
import com.spotify.music.ui.screens.MineScreen
import com.spotify.music.ui.screens.PlayerScreen
import com.spotify.music.ui.screens.SettingsScreen

enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Rounded.Home),
    MINE("我的", Icons.Rounded.Person),
    LOCAL("本地", Icons.Rounded.LibraryMusic),
    SETTINGS("设置", Icons.Rounded.Settings)
}

/** Root composable: decides between the main shell and the full-screen player. */
@Composable
fun MusicApp() {
    val context = LocalContext.current
    val container: AppContainer = App.container(context)

    var tab by rememberSaveable { mutableStateOf(MainTab.HOME.name) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    val selectedTab = MainTab.valueOf(tab)

    val configuration = LocalConfiguration.current

    if (playerOpen) {
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            LandscapePlayerScreen(
                container = container,
                onBack = { playerOpen = false }
            )
        } else {
            PlayerScreen(
                container = container,
                onBack = { playerOpen = false }
            )
        }
        return
    }

    MainShell(
        container = container,
        selectedTab = selectedTab,
        onSelectTab = { tab = it.name },
        onOpenPlayer = { playerOpen = true }
    )
}

/** Main shell: content, bottom playback bar, then DOCK below it. */
@Composable
private fun MainShell(
    container: AppContainer,
    selectedTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    onOpenPlayer: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (selectedTab) {
                MainTab.HOME -> HomeScreen(container, onOpenPlayer)
                MainTab.MINE -> MineScreen(container, onOpenPlayer)
                MainTab.LOCAL -> LocalScreen(container, onOpenPlayer)
                MainTab.SETTINGS -> SettingsScreen(container)
            }
        }
        // bottom playback bar (播放条上移)
        PlayerBar(controller = container.playerController, onOpen = onOpenPlayer)
        // main menu DOCK placed below the playback bar
        NavDock(selected = selectedTab, onSelect = onSelectTab)
    }
}

@Composable
private fun NavDock(selected: MainTab, onSelect: (MainTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 6.dp)
    ) {
        MainTab.values().forEach { t ->
            val active = t == selected
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onSelect(t) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = t.icon,
                    contentDescription = t.label,
                    modifier = Modifier.size(26.dp),
                    tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = t.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}