package com.spotify.music.car

import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import androidx.media3.common.MediaMetadata

/** Field/status constants for the vivo 智能车载 (JoviInCar) lyrics protocol. */
object CarLyricsConstants {
    // Metadata channel (car launcher direct)
    const val METADATA_KEY_LYRICS_LINE = "ucar.media.metadata.LYRICS_LINE"
    const val METADATA_KEY_LYRICS_WHOLE = "ucar.media.metadata.LYRICS_WHOLE"
    const val METADATA_KEY_LYRICS_STATUS = "ucar.media.metadata.LYRICS_STATUS"

    // Extras channel (phone smart-car forward)
    const val EXTRAS_KEY_LYRIC = "music.media.extras.LYRIC"
    const val EXTRAS_KEY_LYRIC_ALLOWED = "music.media.extras.LYRIC_IS_ALLOWED"
    const val EXTRAS_KEY_NOTICE_CAR = "music.media.extras.NOTICE_CAR"

    const val STATUS_SUCCESS = 0L
    const val STATUS_NO_LYRICS = 1L
    const val STATUS_LOADING = 2L
    const val STATUS_FAIL = 3L
}

/**
 * Pushes car-scrolling lyrics over BOTH channels required by vivo JoviInCar:
 * the metadata channel (ucar.media.metadata.*) and the extras channel
 * (music.media.extras.*). See the car-lyrics adaptation guide.
 */
object CarLyricsDelegate {

    fun update(
        session: MediaSessionCompat,
        title: String?,
        artist: String?,
        currentLine: String?,
        wholeLrc: String?,
        isLoading: Boolean,
        enabled: Boolean
    ) {
        val base = android.support.v4.media.MediaMetadataCompat.Builder()
            .putText("android.media.metadata.TITLE", title ?: "")
            .putText("android.media.metadata.ARTIST", artist ?: "")
            .putText("android.media.metadata.ALBUM", "")
            .build()

        val builder = android.support.v4.media.MediaMetadataCompat.Builder(base)

        if (enabled) {
            // current line: never send "-1"
            builder.putText(
                CarLyricsConstants.METADATA_KEY_LYRICS_LINE,
                currentLine?.takeIf { it.isNotBlank() } ?: ""
            )
            when {
                isLoading -> {
                    builder.putText(CarLyricsConstants.METADATA_KEY_LYRICS_WHOLE, "")
                    builder.putLong(CarLyricsConstants.METADATA_KEY_LYRICS_STATUS, CarLyricsConstants.STATUS_LOADING)
                }
                !wholeLrc.isNullOrBlank() -> {
                    builder.putText(CarLyricsConstants.METADATA_KEY_LYRICS_WHOLE, wholeLrc)
                    builder.putLong(CarLyricsConstants.METADATA_KEY_LYRICS_STATUS, CarLyricsConstants.STATUS_SUCCESS)
                }
                else -> {
                    builder.putText(CarLyricsConstants.METADATA_KEY_LYRICS_WHOLE, "-1")
                    builder.putLong(CarLyricsConstants.METADATA_KEY_LYRICS_STATUS, CarLyricsConstants.STATUS_NO_LYRICS)
                }
            }
        }
        session.setMetadata(builder.build())

        val extras = Bundle()
        if (enabled) {
            extras.putBoolean(CarLyricsConstants.EXTRAS_KEY_LYRIC_ALLOWED, true)
            if (!currentLine.isNullOrBlank()) {
                extras.putString(CarLyricsConstants.EXTRAS_KEY_LYRIC, currentLine)
            }
            extras.putBoolean(CarLyricsConstants.EXTRAS_KEY_NOTICE_CAR, true)
        }
        session.setExtras(extras)
    }
}