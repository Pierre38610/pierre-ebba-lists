package com.pierreebba.sharedlists

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingTab: String? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null

        val uris = mutableListOf<Uri>()
        if (result.resultCode == RESULT_OK && result.data != null) {
            val data = result.data!!
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let { uris.add(it) }
                }
            } else {
                data.data?.let { uris.add(it) }
            }
        }
        callback.onReceiveValue(uris.toTypedArray())
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            PartnerStreamManager.startIfAllowed(this)
            Toast.makeText(this, "Notifications enabled! 🔔", Toast.LENGTH_SHORT).show()
        }
        webView.post {
            webView.evaluateJavascript("window.dispatchEvent(new Event('pe-notif-updated'))", null)
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val req = pendingWebPermissionRequest
        pendingWebPermissionRequest = null
        if (req != null) {
            try {
                if (granted) {
                    req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    req.deny()
                }
            } catch (_: Exception) {
            }
        }
        if (!granted) {
            Toast.makeText(this, "Microphone permission is needed to record pronunciation", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NotificationHelper.ensureChannels(this)
        PartnerSyncWorker.schedule(this)

        webView = WebView(this)
        setContentView(webView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val host = uri.host.orEmpty()
                if (host == "pierre38610.github.io") {
                    return false
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (_: Exception) {
                    false
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val tab = pendingTab
                if (!tab.isNullOrBlank()) {
                    pendingTab = null
                    view?.evaluateJavascript(
                        "window.__peOpenTab && window.__peOpenTab('${sanitizeTab(tab)}')",
                        null
                    )
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread {
                    if (request == null) return@runOnUiThread
                    val originHost = request.origin?.host.orEmpty()
                    val wantsAudio = request.resources?.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) == true
                    if (originHost == "pierre38610.github.io" && wantsAudio) {
                        val hasAudioPerm = ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasAudioPerm) {
                            try {
                                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                            } catch (_: Exception) {
                            }
                        } else {
                            try { pendingWebPermissionRequest?.deny() } catch (_: Exception) {}
                            pendingWebPermissionRequest = request
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    } else {
                        try { request.deny() } catch (_: Exception) {}
                    }
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                if (pendingWebPermissionRequest == request) {
                    pendingWebPermissionRequest = null
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = try {
                    fileChooserParams?.createIntent()?.apply {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    } ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                } catch (_: Exception) {
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }

                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (_: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                val input = EditText(this@MainActivity).apply { setText(defaultValue ?: "") }
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm(input.text.toString()) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val initialTab = intent?.getStringExtra(EXTRA_TAB)
        val startUrl = if (!initialTab.isNullOrBlank()) {
            "${AppPrefs.APP_URL}?tab=${sanitizeTab(initialTab)}"
        } else {
            AppPrefs.APP_URL
        }
        webView.loadUrl(startUrl)

        requestNotificationPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        PartnerStreamManager.startIfAllowed(this)
    }

    override fun onResume() {
        super.onResume()
        webView.evaluateJavascript("window.__peRefreshFromCloud && window.__peRefreshFromCloud()", null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tab = intent.getStringExtra(EXTRA_TAB)
        if (!tab.isNullOrBlank()) {
            pendingTab = tab
            webView.evaluateJavascript(
                "window.__peOpenTab && window.__peOpenTab('${sanitizeTab(tab)}')",
                null
            )
        }
    }

    private fun sanitizeTab(raw: String): String {
        return when (raw) {
            "recipes" -> "recipes"
            "words" -> "words"
            else -> "lists"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationHelper.hasPostPermission(this)
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun isNativeApp(): Boolean = true

        @JavascriptInterface
        fun getAppVersion(): String = "1.1.0"

        @JavascriptInterface
        fun syncConfig(jsonStr: String) {
            try {
                val prevUser = AppPrefs.getCurrentUser(this@MainActivity)
                AppPrefs.updateFromBridgeJson(this@MainActivity, jsonStr)
                NoteWidgetProvider.updateAllWidgets(this@MainActivity)
                val newUser = AppPrefs.getCurrentUser(this@MainActivity)
                if (prevUser != newUser && AppPrefs.isLivePushEnabled(this@MainActivity)) {
                    PartnerStreamManager.restartIfAllowed(this@MainActivity)
                }
            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun pinNoteWidget(): Boolean {
            val manager = AppWidgetManager.getInstance(this@MainActivity)
            val provider = ComponentName(this@MainActivity, NoteWidgetProvider::class.java)
            if (!manager.isRequestPinAppWidgetSupported) {
                return false
            }
            val callbackIntent = Intent(this@MainActivity, WidgetPinnedReceiver::class.java)
            val successCallback = PendingIntent.getBroadcast(
                this@MainActivity,
                42,
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return manager.requestPinAppWidget(provider, null, successCallback)
        }

        @JavascriptInterface
        fun hasNotificationPermission(): Boolean {
            return NotificationHelper.hasPostPermission(this@MainActivity)
        }

        @JavascriptInterface
        fun requestNotificationPermission() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                    startActivity(intent)
                }
            }
        }

        @JavascriptInterface
        fun isLivePushEnabled(): Boolean {
            return AppPrefs.isLivePushEnabled(this@MainActivity)
        }

        @JavascriptInterface
        fun setLivePushEnabled(enabled: Boolean) {
            AppPrefs.setLivePushEnabled(this@MainActivity, enabled)
            if (enabled) {
                PartnerStreamManager.startIfAllowed(this@MainActivity)
            } else {
                PartnerStreamManager.stop()
            }
        }

        @JavascriptInterface
        fun sendTestLocalNotification(title: String, body: String, tab: String) {
            NotificationHelper.showPartnerNotification(
                context = this@MainActivity,
                notifId = "test_${System.currentTimeMillis()}",
                title = title,
                body = body,
                tab = sanitizeTab(tab),
                deduplicate = false
            )
        }

        @JavascriptInterface
        fun openBatteryOptimizationSettings() {
            runOnUiThread {
                try {
                    val pm = getSystemService(POWER_SERVICE) as PowerManager
                    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                        @SuppressLint("BatteryLife")
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } else {
                        Toast.makeText(this@MainActivity, "Already unrestricted 🔋", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_TAB = "extra_tab"
    }
}
