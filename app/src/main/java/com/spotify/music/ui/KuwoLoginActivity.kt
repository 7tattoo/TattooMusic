package com.spotify.music.ui

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.spotify.music.App

/**
 * In-app browser login for the Kuwo account. Loads the Kuwo homepage inside a
 * WebView so the user can sign in directly in the app; tapping "完成登录"
 * reads the resulting cookies and stores them as the logged-in session.
 */
class KuwoLoginActivity : Activity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        wv.webViewClient = WebViewClient()
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.loadUrl("https://www.kuwo.cn/")
        webView = wv

        val title = TextView(this).apply {
            text = "登录酷我账号"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dip(16), 0, dip(8), 0)
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        }
        val done = Button(this).apply {
            text = "完成登录"
            setOnClickListener { saveCookieAndFinish() }
        }
        val cancel = Button(this).apply {
            text = "取消"
            setOnClickListener { finish() }
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dip(48), 1f))
            addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dip(48)))
            addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dip(48)))
        }
        val hint = TextView(this).apply {
            text = "请在页面内登录账号，完成后点击右上角「完成登录」"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = android.view.Gravity.CENTER
            setPadding(dip(12), dip(6), dip(12), dip(6))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    private fun saveCookieAndFinish() {
        val cm = CookieManager.getInstance()
        val ck = buildString {
            listOf("https://www.kuwo.cn", "http://www.kuwo.cn", "https://kuwo.cn").forEach { origin ->
                cm.getCookie(origin)?.let { c -> if (c.isNotBlank()) { append(c); append("; ") } }
            }
        }.trimEnd(' ', ';')

        if (ck.isBlank()) {
            Toast.makeText(this, "未获取到登录 Cookie，请先在页面内登录后再点「完成登录」", Toast.LENGTH_LONG).show()
            return
        }

        val container = App.container(this)
        container.settings.setKuwoAccount(ck, null)
        container.api.setAccountCookie(ck)
        Toast.makeText(this, "已登录酷我账号，Cookie 已自动保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        webView?.runCatching { destroy() }
        webView = null
        super.onDestroy()
    }

    private fun dip(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}