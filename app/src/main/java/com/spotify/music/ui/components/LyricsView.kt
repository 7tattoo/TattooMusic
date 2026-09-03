package com.spotify.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.spotify.music.player.LyricStatus
import com.spotify.music.player.PlayerController

/**
 * A synchronized, automatically scrolling lyrics view.
 * Highlights the current line and allows tapping a line to seek.
 */
@Composable
fun LyricsView(
    controller: PlayerController,
    modifier: Modifier = Modifier
) {
    val lyrics by controller.lyrics.collectAsState()
    val idx by controller.currentLyricIndex.collectAsState()
    val status by controller.lyricStatus.collectAsState()

    if (status == LyricStatus.LOADING && lyrics.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("歌词加载中…", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    if (lyrics.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无歌词", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val listState = rememberLazyListState()
    val target = idx.coerceAtLeast(0)
    LaunchedEffect(target, lyrics.size) {
        if (lyrics.isNotEmpty() && target < lyrics.size) {
            listState.animateScrollToItem(target)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = 40.dp)
    ) {
        itemsIndexed(lyrics) { i, line ->
            val active = i == idx
            val emphasis = if (active) FontWeight.Bold else if (i == idx - 1) FontWeight.Medium else FontWeight.Normal
            Text(
                text = line.text.ifBlank { "· · ·" },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { controller.seekTo(line.timeMs) }
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                fontWeight = emphasis,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = if (active) 18.sp else 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** A compact, single-line lyric ticker for narrow car screens. */
@Composable
fun LyricTicker(
    controller: PlayerController,
    modifier: Modifier = Modifier,
    unknownText: String = "暂无歌词"
) {
    val text by controller.currentLyricText.collectAsState()
    val status by controller.lyricStatus.collectAsState()
    val content = when {
        status == LyricStatus.LOADING -> "歌词加载中…"
        !text.isNullOrBlank() -> text!!
        else -> unknownText
    }
    Text(
        text = content,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}