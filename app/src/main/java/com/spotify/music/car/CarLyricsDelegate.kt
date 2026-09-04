package com.spotify.music.car

import android.graphics.Bitmap
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat

/**
 * vivo 车载 lyrics 协议常量。
 *
 * 这是文档明确的"整段 LRC"方案（对齐酷我 12.0.8.0 / vivo 原生）：
 * - ucar 通道只上传 [METADATA_KEY_LYRICS_WHOLE]（整段 LRC + 行级时间戳），
 *   由车机按 MediaSession 的 PlaybackState 播放进度自行滚动。
 * - 原子随身听（vivomusicmix）走 MediaSession.setExtras() 的 lrc_change 事件。
 *
 * 注意：`lrc_change` 事件里的两个 key（`meida` / `meidia`）是 vivo 官方的
 * 拼写错误，必须原样照抄，写成正确拼写反而收不到。
 */
object CarLyricsConstants {
    // ucar metadata 通道：只写整段 LRC
    const val METADATA_KEY_LYRICS_WHOLE = "ucar.media.metadata.LYRICS_WHOLE"
    const val METADATA_KEY_LYRICS_STATUS = "ucar.media.metadata.LYRICS_STATUS"
    const val STATUS_WHOLE_OK = 0L

    // 原子随身听 (vivomusicmix) 能力位：7(播控)|8(歌词)|16(进度/seek) = 31
    const val META_SUPPORT_EVENT = "vivomusicmix.media.metadata.support_event"
    const val SUPPORT_EVENT = 31L

    // lrc_change 事件（vivo 官方 typo 拼写，勿"纠正"）
    const val EXTRAS_ACTION_KEY = "vivomusicmix.meida.extra.key.action"
    const val EXTRAS_ACTION_LRC_CHANGE = "vivomusicmix.extra.lrc_change"
    const val EXTRAS_LYRIC_KEY = "vivomusicmix.extra.key.lyric"
    const val EXTRAS_MEDIA_ID_KEY = "vivomusicmix.extra.key.meidia_id"

    // 原子随身听 lrc_change 的重发间隔
    const val ATOMIC_RESEND_MS = 25_000L
}

/**
 * 向车载会话镜像"整段 LRC"歌词，并投递原子随身听 lrc_change 事件。
 *
 * 严格遵循 vivo 车机歌词适配文档：
 * 1. 只写整段 LRC（[CarLyricsConstants.METADATA_KEY_LYRICS_WHOLE]），
 *    由车机按播放进度自行滚动——绝不推"当前第几行"。
 * 2. 有整段歌词才写歌词字段；**没有就原样返回，绝不写负状态**。
 * 3. metadata 只在内容（歌名/歌手/封面/LRC）真正变化时才重推，避免 150ms
 *    镜像循环反复推送把车机切歌时的滚动状态打回/重置（"歌词跳回开头"的根因）。
 * 4. 原子随身听走 extras 的 lrc_change 事件，切歌/歌词就绪时发一次，之后每
 *    25s 兜底重发一次；无事可做时不推空 extras。
 */
object CarLyricsDelegate {

    private data class MetaSig(
        val title: String?,
        val artist: String?,
        val artRef: Int,
        val whole: String?,
        val mediaId: String?
    )

    // 上一次真正推送给会话的 metadata 签名（用于去抖）
    private var presented: MetaSig? = null

    // 原子随身听去抖状态（主线程安全：只被 150ms 主线程 loop 访问）
    private var atomicTrackId: String? = null
    private var atomicLrc: String? = null
    private var atomicSentAt: Long = 0L

    fun update(
        session: MediaSessionCompat,
        title: String?,
        artist: String?,
        wholeLrc: String?,
        enabled: Boolean,
        artBitmap: Bitmap?,
        artUri: String?,
        mediaId: String?
    ) {
        // 只包括"可用"的整段 LRC；禁用或为空则按"无歌词"处理
        val whole = if (enabled && !wholeLrc.isNullOrBlank()) wholeLrc else null

        val artRef = artBitmap?.hashCode() ?: artUri?.hashCode() ?: 0
        val sig = MetaSig(title, artist, artRef, whole, mediaId)
        if (presented != sig) {
            presented = sig

            val base = android.support.v4.media.MediaMetadataCompat.Builder()
                .putText("android.media.metadata.TITLE", title ?: "")
                .putText("android.media.metadata.ARTIST", artist ?: "")
                .putText("android.media.metadata.ALBUM", "")
                // 原子随身听要求 lrc_change 事件的 meidia_id 必须等于当前歌曲的
                // MEDIA_ID（MusicWidgetManager E0()/z1()），否则隐藏歌词。
                .putString("android.media.metadata.MEDIA_ID", mediaId ?: "")

            // 封面：Bitmap 最可靠，URI 作轻量兜底
            if (artBitmap != null) {
                base
                    .putBitmap("android.media.metadata.ALBUM_ART", artBitmap)
                    .putBitmap("android.media.metadata.ART", artBitmap)
                    .putBitmap("android.media.metadata.DISPLAY_ICON", artBitmap)
            }
            if (!artUri.isNullOrBlank()) {
                base
                    .putString("android.media.metadata.ALBUM_ART_URI", artUri)
                    .putString("android.media.metadata.DISPLAY_ICON_URI", artUri)
            }

            // 原子随身听能力位（含 8=歌词）：始终写入，组件在连接瞬间就据此
            // 决定是否渲染歌词区域，与歌词是否已就绪无关。
            base.putLong(CarLyricsConstants.META_SUPPORT_EVENT, CarLyricsConstants.SUPPORT_EVENT)

            // 铁律：有整段歌词才写歌词字段；没有则 metadata 不含任何歌词 key，
            // 绝不上报 LYRICS_STATUS 为"无歌词/加载中"这类负状态。
            if (whole != null) {
                base
                    .putText(CarLyricsConstants.METADATA_KEY_LYRICS_WHOLE, whole)
                    .putLong(CarLyricsConstants.METADATA_KEY_LYRICS_STATUS, CarLyricsConstants.STATUS_WHOLE_OK)
            }

            runCatching { session.setMetadata(base.build()) }
        }

        // 原子随身听 lrc_change：等 metadata（能力位+整段歌词）就绪后再发，
        // 切歌/歌词就绪立即推一次，之后每 25s 兜底重发。
        pushAtomic(session, mediaId, whole)
    }

    /**
     * 原子随身听 lrc_change 事件。
     * 严格遵循 vivo 协议：
     * - 换歌时先发一次"空歌词事件"（action=lrc_change + 新曲 meidia_id + 空 lyric），
     *   清除原子内存中上一首的歌词；若新歌已就绪则紧随其后立即补发完整 LRC。
     * - 同曲时：歌词变化或超过 25s 兜底重发一次；无歌词可发时不推空 extras。
     */
    private fun pushAtomic(session: MediaSessionCompat, mediaId: String?, whole: String?) {
        if (mediaId == null) {
            atomicTrackId = null
            atomicLrc = null
            return
        }
        val now = System.currentTimeMillis()
        if (atomicTrackId != mediaId) {
            // 换歌：先清上一首（新 meidia_id + 空 lyric），就绪则紧跟补发完整 LRC
            sendAtomicEvent(session, mediaId, "")
            atomicTrackId = mediaId
            atomicLrc = whole
            atomicSentAt = now
            if (whole != null) sendAtomicEvent(session, mediaId, whole)
            return
        }
        // 同曲：无歌词不推；歌词变化或超过 25s 兜底重发
        if (whole == null) return
        val changed = atomicLrc != whole
        if (!changed && now - atomicSentAt < CarLyricsConstants.ATOMIC_RESEND_MS) return
        atomicLrc = whole
        atomicSentAt = now
        sendAtomicEvent(session, mediaId, whole)
    }

    private fun sendAtomicEvent(session: MediaSessionCompat, mediaId: String?, lyric: String?) {
        runCatching {
            val b = Bundle()
            b.putString(CarLyricsConstants.EXTRAS_ACTION_KEY, CarLyricsConstants.EXTRAS_ACTION_LRC_CHANGE)
            b.putString(CarLyricsConstants.EXTRAS_MEDIA_ID_KEY, mediaId ?: "")
            b.putString(CarLyricsConstants.EXTRAS_LYRIC_KEY, lyric ?: "")
            session.setExtras(b)
        }
    }
}