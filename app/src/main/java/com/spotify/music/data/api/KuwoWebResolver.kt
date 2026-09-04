package com.spotify.music.data.api

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Plan A: WebView-based stream resolver for Kuwo.
 *
 * OkHttp (a curl-like client) cannot unlock VIP/paid tracks because Kuwo's WAF
 * rejects requests that do not come from a real Chromium network stack, even
 * with a correct Cookie + Secret. This resolver rides on a real (hidden) WebView:
 * it loads the kuwo homepage to let the page's own JS establish the anti-bot
 * session + a live `Hm_Iuvt_*` token, then issues a same-origin XMLHttpRequest
 * to the playUrl API from inside that browser context (Chromium fingerprint,
 * cookies auto-attached) with a Secret hashed from the CURRENT token value.
 *
 * Because [CookieManager] is a process-wide singleton, this WebView automatically
 * reuses the account session created by the in-app login (KuwoLoginActivity).
 */
class KuwoWebResolver(
    private val context: Context,
    private val logFile: java.io.File? = null
) {
    /** Timestamped diagnostic, appended to [logFile] so device-side runs are inspectable. */
    private fun log(msg: String) {
        val f = logFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.appendText(
                "${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} [WEBVIEW] $msg\n"
            )
        }
    }

    companion object {
        private const val BASE = "https://www.kuwo.cn"
        private val HITOKEN = Regex("(Hm_Iuvt_[A-Za-z0-9]+)=([^;]+)")
    }

    @Volatile
    private var webView: WebView? = null
    private val pageReady = CompletableDeferred<Unit>()
    @Volatile
    private var warmed = false

    /** Create the WebView lazily (must run on the main thread). */
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val wv = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString = KuwoSecret.DESKTOP_UA
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!pageReady.isCompleted) pageReady.complete(Unit)
                }
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        webView = wv
        return wv
    }

    /**
     * Push a logged-in cookie string into the shared CookieManager, so the
     * resolver's WebView can serve VIP songs for an account that was logged in
     * by pasting a cookie (in-app WebView login already lands here by itself).
     * Pass null/blank to evict the kuwo account cookies.
     */
    fun syncAccountCookie(rawCookie: String?) {
        // Must run on the main thread for cookie mutation.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { syncAccountCookie(rawCookie) }
            return
        }
        val cm = CookieManager.getInstance()
        if (rawCookie.isNullOrBlank()) {
            runCatching {
                cm.getCookie(BASE)?.split(';')?.forEach { pair ->
                    val name = pair.substringBefore('=').trim()
                    if (name.isNotBlank()) {
                        cm.setCookie(
                            BASE,
                            "$name=; domain=kuwo.cn; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT"
                        )
                    }
                }
                cm.flush()
            }
            return
        }
        rawCookie.split(';').forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@forEach
            val name = pair.substring(0, idx).trim()
            val value = pair.substring(idx + 1).trim()
            if (name.isBlank()) return@forEach
            runCatching {
                cm.setCookie(BASE, "$name=$value; domain=.kuwo.cn; path=/")
                cm.setCookie(BASE, "$name=$value; domain=kuwo.cn; path=/")
            }
        }
        runCatching { cm.flush() }
        // account cookies changed: force a reload next resolve so the page
        // re-establishes the Hm_Iuvt token under the new session.
        if (!pageReady.isCompleted) return
        warmed = false
    }

    /** Load the homepage when needed and wait until the Hm_Iuvt token is live. */
    private suspend fun warmUp(): Boolean = withContext(Dispatchers.Main) {
        val wv = ensureWebView()
        if (!warmed) {
            if (!pageReady.isCompleted) wv.loadUrl(BASE)
            val finished = withTimeoutOrNull(15_000) { pageReady.await() }
            if (finished == null) {
                log("warmup timeout")
                return@withContext false
            }
            // Give the anti-bot beacon a moment to write Hm_Iuvt after load.
            var found = false
            repeat(6) {
                if (currentTokenOnMain() != null) { found = true; return@repeat }
                delay(500)
            }
            if (!found) log("no Hm_Iuvt token after warmup")
            warmed = true
        }
        true
    }

    private fun currentTokenOnMain(): Pair<String, String>? {
        val cookie = CookieManager.getInstance().getCookie(BASE)
        val m = HITOKEN.find(cookie ?: "")
        return m?.let { it.groupValues[1] to it.groupValues[2].trim() }
    }

    /**
     * Resolve a playable stream URL from inside the WebView. Returns null when
     * the session/token is missing, when www.kuwo.cn still refuses the request,
     * or when the payload carries no playable URL (rare non-VIP rejection).
     */
    suspend fun resolvePlayUrl(mid: String, br: String = "128kmp3"): String? =
        withContext(Dispatchers.Main) {
            val ok = warmUp()
            if (!ok) return@withContext null
            val token = currentTokenOnMain()
            if (token == null) {
                log("resolvePlayUrl mid=$mid: no live Hm_Iuvt token")
                return@withContext null
            }
            // Diagnostics: confirm the request really carries the account / membership
            // cookies (values hidden). If the member cookie is absent from this list
            // the server is right to answer "该歌曲为付费内容".
            val cookieKeys = (CookieManager.getInstance().getCookie(BASE) ?: "")
                .split(';').mapNotNull { it.substringBefore('=').trim().takeIf { k -> k.isNotBlank() } }
                .distinct().sorted()
            val memberHint = cookieKeys.any {
                it.equals("kwtank", true) || it.equals("kwwise_member", true) ||
                    it.startsWith("kwtank") || it.equals("kwtp", true) ||
                    it.equals("kwmobiletoken", true) || it.startsWith("kw_")
            }
            log("resolvePlayUrl mid=$mid cookieKeys=[${cookieKeys.joinToString(",")}] memberHint=$memberHint")
            // secretFor(name, value) derives h(value, name) — exactly the reference
            // `h(Object(v)(f), f)` (message = token VALUE, password = token NAME/key).
            // Passing them reversed produced a wrong Secret and "The request is illegal!".
            val secret = KuwoSecret.secretFor(token.first, token.second)
            if (secret == null) {
                // Real browser context + valid session cookie may already satisfy the
                // WAF; don't hard-fail just because the hash is unavailable.
                log("resolvePlayUrl mid=$mid: secret generation failed, trying without Secret header")
            }
            val id = mid.removePrefix("MUSIC_")
            val reqId = UUID.randomUUID().toString()
            val url = "$BASE/api/v1/www/music/playUrl?mid=$id&type=music" +
                "&httpsStatus=1&plat=web_www&from=&br=${jsStr(br)}&reqId=$reqId"
            val js =
                "(function(){var u='${jsStr(url)}';var s=${if (secret != null) "'${jsStr(secret)}'" else "null"};" +
                    "var x=new XMLHttpRequest();x.open('GET',u,false);" +
                    "if(s){x.setRequestHeader('Secret',s);}" +
                    "x.setRequestHeader('Referer','$BASE/');" +
                    "var csrf=(document.cookie.match(/(^|;)\\s*kw_token=([^;]+)/)||[])[2];" +
                    "if(csrf){x.setRequestHeader('csrf',csrf);}" +
                    "try{x.send();}catch(e){return JSON.stringify({s:0,b:'__ERR__'+(e&&e.message||e)});}" +
                    "return JSON.stringify({s:x.status,b:typeof x.responseText==='string'?x.responseText:''});})()"

            val raw = withTimeoutOrNull(25_000) {
                CompletableDeferred<String?>().also { d ->
                    runCatching { ensureWebView().evaluateJavascript(js) { d.complete(it) } }
                        .onFailure { d.complete(null) }
                }.await()
            } ?: return@withContext logAndNull("resolvePlayUrl mid=$mid: js timeout")

            val json = Json { ignoreUnknownKeys = true }
            var status = -1
            var body: String? = null
            runCatching {
                // evaluateJavascript serializes the return value as JSON. Since our
                // IIFE returns JSON.stringify({...}), the callback value is a JSON
                // *string* literal: "\"{\\\"s\\\":200,...}\"". Unwrap the outer string
                // before treating the content as the {s,b} object, otherwise it is
                // parsed as a JsonPrimitive and body stays null ("bad js wrapper").
                val root = json.parseToJsonElement(raw)
                val inner = when (root) {
                    is JsonObject -> root
                    is JsonPrimitive -> if (root.isString)
                        json.parseToJsonElement(root.content) as? JsonObject else null
                    else -> null
                }
                if (inner != null) {
                    (inner["s"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { status = it }
                    body = (inner["b"] as? JsonPrimitive)?.content
                } else {
                    log(
                        "resolvePlayUrl mid=$mid: js root not an object " +
                            "(root=${root::class.simpleName})"
                    )
                }
            }.onFailure { log("resolvePlayUrl mid=$mid: js parse err=${it.message}") }
            val b: String = body
                ?: return@withContext logAndNull(
                    "resolvePlayUrl mid=$mid: bad js wrapper raw=${raw?.take(200)}"
                )
            if (status != 200) {
                return@withContext logAndNull("resolvePlayUrl mid=$mid: http=$status body=${b.take(100)}")
            }
            val playUrl = runCatching {
                val root = json.parseToJsonElement(b)
                val data = (root as? JsonObject)?.get("data") as? JsonObject
                (data?.get("url") as? JsonPrimitive)?.content
            }.getOrNull()

            if (playUrl.isNullOrBlank() || !playUrl.startsWith("http")) {
                return@withContext logAndNull("resolvePlayUrl mid=$mid: no data.url body=${b.take(120)}")
            }
            log("resolvePlayUrl ok mid=$mid")
            playUrl
        }

    private fun logAndNull(msg: String): String? {
        log(msg)
        return null
    }

    fun destroy() {
        val wv = webView ?: return
        webView = null
        runCatching { Handler(Looper.getMainLooper()).post { wv.destroy() } }
    }
}

/** Escape [s] so it can be embedded inside a single-quoted JS string literal. */
private fun jsStr(s: String): String = s
    .replace("\\", "\\\\")
    .replace("'", "\\'")
    .replace("\n", "\\n")
    .replace("\r", "")