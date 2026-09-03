package com.spotify.music.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/** App settings backed by SharedPreferences, exposed as reactive flows. */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("tattoo_settings", Context.MODE_PRIVATE)

    // ---- sleep timer ----
    val sleepMinutesLeft = MutableStateFlow(0L)

    fun setSleepMinutes(minutes: Long) { sleepMinutesLeft.value = minutes }

    fun clearSleep() { sleepMinutesLeft.value = 0L }

    // ---- directory filter (ignored dirs for local music) ----
    val ignoredDirs = MutableStateFlow(prefs.getStringSet("ignore_dirs", emptySet())?.toSet() ?: emptySet())

    fun toggleIgnoreDir(path: String, ignore: Boolean) {
        val cur = ignoredDirs.value.toMutableSet()
        if (ignore) cur.add(path) else cur.remove(path)
        prefs.edit().putStringSet("ignore_dirs", cur).apply()
        ignoredDirs.value = cur.toSet()
    }

    // ---- music root dirs (scan only the chosen directories; supports multiple) ----
    val musicRoots = MutableStateFlow<List<String>>(prefs.getStringSet("music_roots", emptySet())?.toList() ?: emptyList())
    fun setMusicRoots(paths: List<String>) {
        prefs.edit().putStringSet("music_roots", paths.toSet()).apply()
        musicRoots.value = paths
    }
    fun addMusicRoot(path: String) {
        val cur = musicRoots.value.toMutableList()
        if (path !in cur) cur.add(path)
        setMusicRoots(cur)
    }
    fun removeMusicRoot(path: String) {
        setMusicRoots(musicRoots.value.filter { it != path })
    }

    // ---- local music list sort order (auto-remembered) ----
    private fun readLocalSort(): LocalSort =
        LocalSort.values().firstOrNull { it.key == prefs.getString("local_sort", LocalSort.TITLE_ASC.key) }
            ?: LocalSort.TITLE_ASC

    val localSort = MutableStateFlow(readLocalSort())

    fun setLocalSort(s: LocalSort) {
        prefs.edit().putString("local_sort", s.key).apply()
        localSort.value = s
    }

    // ---- usb exclusive ----
    val usbExclusiveEnabled = MutableStateFlow(prefs.getBoolean("usb_exclusive", false))
    fun setUsbExclusive(v: Boolean) { prefs.edit().putBoolean("usb_exclusive", v).apply(); usbExclusiveEnabled.value = v }

    val usbVolume = MutableStateFlow((prefs.getFloat("usb_volume", 1f) * 100).toInt())
    fun setUsbVolume(percent: Int) { prefs.edit().putFloat("usb_volume", percent / 100f).apply(); usbVolume.value = percent }

    // ---- jovi car lyrics ----
    val carLyricsEnabled = MutableStateFlow(prefs.getBoolean("car_lyrics", true))
    fun setCarLyrics(v: Boolean) { prefs.edit().putBoolean("car_lyrics", v).apply(); carLyricsEnabled.value = v }

    // ---- theme ----
    val darkTheme = MutableStateFlow(prefs.getBoolean("dark_theme", false))
    fun setDarkTheme(v: Boolean) { prefs.edit().putBoolean("dark_theme", v).apply(); darkTheme.value = v }

    // ---- kuwo account (cookie-based login) ----
    val kuwoCookie = MutableStateFlow(prefs.getString("kuwo_cookie", null))
    val kuwoNickname = MutableStateFlow(prefs.getString("kuwo_nickname", "刺青用户"))

    val isKuwoLoggedIn: Boolean get() = !kuwoCookie.value.isNullOrBlank()

    fun setKuwoAccount(cookie: String?, nickname: String?) {
        val nick = nickname?.takeIf { it.isNotBlank() } ?: "刺青用户"
        prefs.edit().apply {
            if (cookie.isNullOrBlank()) remove("kuwo_cookie") else putString("kuwo_cookie", cookie)
            putString("kuwo_nickname", nick)
        }.apply()
        kuwoCookie.value = cookie
        kuwoNickname.value = nick
    }

    fun clearKuwoAccount() = setKuwoAccount(null, "刺青用户")
}

/** Local music list sort options. */
enum class LocalSort(val key: String, val label: String) {
    TITLE_ASC("title_asc", "标题·升序"),
    TITLE_DESC("title_desc", "标题·降序"),
    ARTIST("artist", "歌手"),
    ALBUM("album", "专辑"),
    DURATION("duration", "时长"),
    MODIFIED("modified", "最近修改")
}