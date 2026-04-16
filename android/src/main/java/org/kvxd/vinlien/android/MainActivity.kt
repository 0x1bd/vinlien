package org.kvxd.vinlien.android

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Bundle
import android.os.IBinder
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetDialog

class MainActivity : AppCompatActivity(), MusicService.WebCommandListener {

    companion object {
        const val PREF_FILE = "vinlien_prefs"
        const val PREF_SERVER_URL = "server_url"
    }

    private lateinit var prefs: SharedPreferences
    private var webView: WebView? = null
    private var musicService: MusicService? = null
    private var serviceBound = false
    private var offlineLoadAttempted = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            musicService = (binder as MusicService.LocalBinder).getService()
            musicService?.webListener = this@MainActivity
            serviceBound = true
            webView?.let { setupAndLoadWebView(it) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            musicService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        prefs = getSharedPreferences(PREF_FILE, MODE_PRIVATE)

        if (prefs.getString(PREF_SERVER_URL, "").isNullOrBlank()) {
            showSetupScreen()
        } else {
            showMainScreen()
            startAndBindService()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView?.canGoBack() == true) webView?.goBack()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        val url = prefs.getString(PREF_SERVER_URL, "") ?: return
        val currentUrl = webView?.url ?: return
        if (!currentUrl.startsWith(url)) {
            webView?.loadUrl(url)
        }
    }

    override fun onDestroy() {
        networkCallback?.let {
            try {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (_: Exception) {}
            networkCallback = null
        }
        if (serviceBound) {
            musicService?.webListener = null
            unbindService(serviceConnection)
            serviceBound = false
        }
        webView?.destroy()
        super.onDestroy()
    }

    private fun showSetupScreen() {
        setContentView(R.layout.activity_setup)

        val root = findViewById<ScrollView>(R.id.setupRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        val urlInput = findViewById<EditText>(R.id.urlInput)
        val connectBtn = findViewById<Button>(R.id.connectBtn)

        connectBtn.setOnClickListener { attemptConnect(urlInput.text.toString()) }
        urlInput.setOnEditorActionListener { _, _, _ -> attemptConnect(urlInput.text.toString()); true }
    }

    private fun attemptConnect(rawUrl: String) {
        val url = rawUrl.trim().let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
        }
        if (url.isBlank()) return
        prefs.edit { putString(PREF_SERVER_URL, url) }
        showMainScreen()
        startAndBindService()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showMainScreen() {
        setContentView(R.layout.activity_main)

        val container = findViewById<FrameLayout>(R.id.webContainer)
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val wv = WebView(this).also { webView = it }
        container.addView(wv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        startNetworkMonitoring()
        if (serviceBound) setupAndLoadWebView(wv)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupAndLoadWebView(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            userAgentString = "$userAgentString VinlienAndroid/1.0"
        }

        musicService?.let { svc ->
            wv.addJavascriptInterface(VinlienBridge(svc), "VinlienAndroid")
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                offlineLoadAttempted = false
                if (view.settings.cacheMode != WebSettings.LOAD_DEFAULT) {
                    view.settings.cacheMode = WebSettings.LOAD_DEFAULT
                }
                injectBridge(view)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (request.url.scheme == "vinlien") {
                    if (request.url.host == "settings") showSettingsSheet()
                    return true
                }
                return false
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (!request.isForMainFrame) return
                if (!isNetworkAvailable() && !offlineLoadAttempted) {
                    offlineLoadAttempted = true
                    view.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    val serverUrl = prefs.getString(PREF_SERVER_URL, "") ?: return
                    view.loadUrl(serverUrl)
                } else {
                    offlineLoadAttempted = false
                    view.settings.cacheMode = WebSettings.LOAD_DEFAULT
                    showErrorPage(view, offline = !isNetworkAvailable())
                }
            }
        }
        wv.webChromeClient = WebChromeClient()

        wv.setOnLongClickListener { showSettingsSheet(); true }

        val url = prefs.getString(PREF_SERVER_URL, "") ?: return
        wv.loadUrl(url)
    }

    private fun injectBridge(wv: WebView) {
        val js = """
            (function() {
                if (window.__vinlienBridgeInstalled) return;
                window.__vinlienBridgeInstalled = true;

                window.vinlienElectron = {
                    updateMedia: function(meta) {
                        try {
                            VinlienAndroid.updateMedia(
                                String(meta && meta.title   || ''),
                                String(meta && meta.artist  || ''),
                                String(meta && meta.album   || ''),
                                String(meta && meta.artwork || '')
                            );
                        } catch (e) {}
                    },
                    updatePosition: function(times) {
                        try {
                            VinlienAndroid.updatePosition(
                                +(times && times.position || 0),
                                +(times && times.duration || 0)
                            );
                        } catch (e) {}
                    },
                    updatePlayState: function(playing) {
                        try { VinlienAndroid.updatePlayState(!!playing); } catch (e) {}
                    }
                };
            })();
        """.trimIndent()
        wv.evaluateJavascript(js, null)
    }

    private fun showErrorPage(wv: WebView, offline: Boolean = false) {
        val url = prefs.getString(PREF_SERVER_URL, "") ?: ""
        val title = if (offline) "You&#39;re offline" else "Cannot connect to server"
        val body  = if (offline)
            "No network connection. Connect to your home network to reach the server."
        else
            "Make sure Vinlien is running at<br><code>$url</code>"
        val html = """
            <!DOCTYPE html><html><head>
            <meta name='viewport' content='width=device-width,initial-scale=1'>
            <style>
              * { box-sizing:border-box; margin:0; padding:0; }
              body { background:#121212; color:#fff; font-family:sans-serif;
                     display:flex; flex-direction:column; align-items:center;
                     justify-content:center; min-height:100vh; padding:32px; text-align:center; }
              svg  { margin-bottom:24px; }
              h2   { font-size:20px; font-weight:700; margin-bottom:8px; }
              p    { color:#a3a3a3; font-size:14px; line-height:1.5; margin-bottom:32px; }
              code { background:#282828; padding:2px 6px; border-radius:4px; }
              .btn-primary   { display:block; width:100%; background:#2563eb; color:#fff;
                               border:none; padding:12px 32px; font-size:15px; font-weight:600;
                               border-radius:8px; cursor:pointer; margin-bottom:12px; }
              .btn-secondary { display:block; width:100%; background:transparent; color:#a3a3a3;
                               border:1px solid #404040; padding:10px 24px; font-size:14px;
                               border-radius:8px; cursor:pointer; }
            </style></head>
            <body>
              <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#a3a3a3" stroke-width="1.5">
                <path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/>
              </svg>
              <h2>$title</h2>
              <p>$body</p>
              <button class='btn-primary' onclick="location.reload()">Retry</button>
              <button class='btn-secondary' onclick="location.href='vinlien://settings'">Change server address</button>
            </body></html>
        """.trimIndent()
        wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun showSettingsSheet() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_settings, null)

        val urlInput  = view.findViewById<EditText>(R.id.urlInput)
        val saveBtn   = view.findViewById<Button>(R.id.saveBtn)
        val statusTv  = view.findViewById<TextView>(R.id.statusText)

        urlInput.setText(prefs.getString(PREF_SERVER_URL, ""))

        val online = isNetworkAvailable()
        statusTv.text = if (online) "● Connected" else "● Offline"
        statusTv.setTextColor(
            ContextCompat.getColor(this, if (online) R.color.success else R.color.danger)
        )

        fun save() {
            val raw = urlInput.text.toString().trim()
            if (raw.isBlank()) return
            val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw
                      else "http://$raw"
            prefs.edit { putString(PREF_SERVER_URL, url) }
            sheet.dismiss()
            webView?.settings?.cacheMode = WebSettings.LOAD_DEFAULT
            offlineLoadAttempted = false
            webView?.loadUrl(url)
        }

        saveBtn.setOnClickListener { save() }
        urlInput.setOnEditorActionListener { _, _, _ -> save(); true }

        sheet.setContentView(view)
        sheet.show()
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetwork != null
    }

    private fun startNetworkMonitoring() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    val currentUrl = webView?.url ?: return@runOnUiThread
                    val serverUrl  = prefs.getString(PREF_SERVER_URL, "") ?: return@runOnUiThread
                    if (currentUrl.startsWith("data:")) {
                        webView?.settings?.cacheMode = WebSettings.LOAD_DEFAULT
                        offlineLoadAttempted = false
                        webView?.loadUrl(serverUrl)
                    }
                }
            }
        }
        networkCallback = callback
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        } catch (_: Exception) {}
    }

    override fun onPlay()       = evalJs("window.vinlienControl?.play()")
    override fun onMusicPause() = evalJs("window.vinlienControl?.pause()")
    override fun onNext()       = evalJs("window.vinlienControl?.next()")
    override fun onPrev()       = evalJs("window.vinlienControl?.prev()")

    override fun onSeekTo(positionSeconds: Double) {
        evalJs("window.vinlienControl?.seekTo($positionSeconds)")
    }

    private fun evalJs(js: String) {
        webView?.post {
            webView?.evaluateJavascript("try { $js } catch(_) {}", null)
        }
    }

    private fun startAndBindService() {
        bindService(
            Intent(this, MusicService::class.java),
            serviceConnection,
            BIND_AUTO_CREATE
        )
    }
}
