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

    // ---- music root dir filter (scan only a chosen directory) ----
    val musicRoot = MutableStateFlow(prefs.getString("music_root", null))
    fun setMusicRoot(path: String?) {
        prefs.edit().putString("music_root", path).apply()
        musicRoot.value = path
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