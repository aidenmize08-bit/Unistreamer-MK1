package com.example.streamer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var urlBox: EditText
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var sniffed: String? = null
    private var pageHost = ""

    // Ad/tracker/popup-network hosts. Extend this list freely.
    private val blocked = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "adnxs.com", "popads.net", "popcash.net",
        "propellerads.com", "onclickads.net", "exoclick.com", "exosrv.com",
        "juicyads.com", "trafficjunky.com", "adsterra.com", "highperformanceformat.com",
        "revcontent.com", "taboola.com", "outbrain.com", "mgid.com", "adcash.com",
        "clickadu.com", "hilltopads.net", "ad-maven.com", "admaven.com",
        "pushwoosh.com", "zeropark.com", "richpush.co", "a-ads.com", "yllix.com"
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun siteOf(host: String?): String {
        val p = (host ?: "").split(".")
        return if (p.size >= 2) p.takeLast(2).joinToString(".") else host ?: ""
    }

    private fun isBlocked(host: String?) =
        host != null && blocked.any { host == it || host.endsWith(".$it") }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        urlBox = EditText(this).apply {
            hint = "Paste link"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val go = Button(this).apply { text = "Go" }
        val dl = Button(this).apply { text = "Save" }
        bar.addView(urlBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(go)
        bar.addView(dl)

        web = WebView(this)
        col.addView(bar)
        col.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(col)
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url
                if (isBlocked(u.host)) {
                    return WebResourceResponse("text/plain", "utf-8", null)
                }
                val s = u.toString().lowercase()
                if (Regex("\\.(mp4|webm|m3u8|mpd)(\\?|$)").containsMatchIn(s)) sniffed = u.toString()
                return null
            }

            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                if (u.scheme != "http" && u.scheme != "https") return true // intent://, market:// etc.
                if (isBlocked(u.host)) return true
                // Block redirects to other sites that the user didn't tap.
                if (r.isForMainFrame && !r.hasGesture() && siteOf(u.host) != pageHost) return true
                return false
            }

            override fun onPageStarted(v: WebView, url: String, f: android.graphics.Bitmap?) {
                pageHost = siteOf(Uri.parse(url).host)
                urlBox.setText(url)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            // Popups / new windows are always refused.
            override fun onCreateWindow(v: WebView, d: Boolean, g: Boolean, m: android.os.Message) = false

            override fun onShowCustomView(view: View, cb: CustomViewCallback) {
                customView = view; customCb = cb
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }

            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }
                customView = null
                customCb?.onCustomViewHidden()
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }

        web.setDownloadListener { url, ua, cd, mime, _ -> startDownload(url, ua, mime) }

        go.setOnClickListener { load(urlBox.text.toString()) }
        urlBox.setOnEditorActionListener { _, _, _ -> load(urlBox.text.toString()); true }
        dl.setOnClickListener {
            val s = sniffed
            when {
                s == null -> toast("No video detected yet. Press play first.")
                s.contains(".m3u8") || s.contains(".mpd") ->
                    toast("Stream playlist detected (HLS/DASH). Segment download not added yet.")
                else -> startDownload(s, web.settings.userAgentString, null)
            }
        }

        val shared = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        shared?.let { load(it) }
    }

    private fun load(input: String) {
        var u = input.trim()
        if (u.isEmpty()) return
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        sniffed = null
        pageHost = siteOf(Uri.parse(u).host)
        web.loadUrl(u)
    }

    private fun startDownload(url: String, ua: String?, mime: String?) {
        try {
            val name = URLUtil.guessFileName(url, null, mime)
            val req = DownloadManager.Request(Uri.parse(url))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            ua?.let { req.addRequestHeader("User-Agent", it) }
            req.addRequestHeader("Referer", web.url ?: url)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("Downloading $name")
        } catch (e: Exception) {
            toast("Download failed: ${e.message}")
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) { web.webChromeClient?.onHideCustomView(); return true }
            if (web.canGoBack()) { web.goBack(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
