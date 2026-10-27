package moe.ouom.neriplayer.activity.auth

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.auth.web.clearWebViewLoginState
import moe.ouom.neriplayer.platform.qqmusic.api.auth.QQMusicCredential
import moe.ouom.neriplayer.platform.qqmusic.api.auth.isTrustedQQMusicLoginUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Dedicated :qq_music_login process/WebView directory. Passwords remain in the official page. */
class QQMusicWebLoginActivity : ComponentActivity() {
    companion object { const val RESULT_AUTH_JSON = "qq_music_web_auth" }
    private lateinit var browser: WebView
    private lateinit var toolbar: MaterialToolbar
    private val popups = mutableListOf<Pair<Dialog, WebView>>()
    private val back = WebLoginBackNavigation(
        canGoBack = { popups.isNotEmpty() || (this::browser.isInitialized && browser.canGoBack()) },
        goBack = { popups.lastOrNull()?.first?.dismiss() ?: browser.goBack() })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (finishIfWebViewUnavailable()) return
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toolbar = MaterialToolbar(this).apply {
            title = getString(CoreCommonR.string.qq_music_web_login)
            setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel)
            setNavigationOnClickListener { finish() }
            menu.add(getString(CoreCommonR.string.qq_music_save_login))
                .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { captureCredentials(); true }
        }
        browser = newBrowser()
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        root.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, back.callback)
        lifecycleScope.launch {
            // All cookies cleared here belong only to this provider's dedicated login process.
            clearWebViewLoginState()
            browser.loadUrl("https://y.qq.com/portal/profile.html")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newBrowser(): WebView = WebView(this).apply {
        isSaveEnabled = false
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            @Suppress("DEPRECATION")
            saveFormData = false
            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(true)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && !isTrustedQQMusicLoginUrl(request.url.toString())
            override fun onPageFinished(view: WebView, url: String) {
                toolbar.subtitle = url.toHttpUrlOrNull()?.host.orEmpty()
                back.refresh()
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean,
                resultMsg: android.os.Message): Boolean {
                if (!isUserGesture) return false
                val popup = newBrowser()
                val dialog = Dialog(this@QQMusicWebLoginActivity)
                dialog.setContentView(popup)
                dialog.setOnDismissListener {
                    popups.removeAll { it.second === popup }
                    popup.destroy()
                    back.refresh()
                }
                popups += dialog to popup
                dialog.show()
                dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                back.refresh()
                return true
            }
            override fun onCloseWindow(window: WebView) { popups.firstOrNull { it.second === window }?.first?.dismiss() }
        }
    }

    private fun captureCredentials() {
        val manager = CookieManager.getInstance()
        val captured = QQMusicCredential.fromCookieHeaders(listOf("https://y.qq.com/", "https://u.y.qq.com/")
            .map { manager.getCookie(it).orEmpty() })
        if (captured == null) {
            Toast.makeText(this, getString(CoreCommonR.string.qq_music_login_not_ready), Toast.LENGTH_LONG).show()
            return
        }
        setResult(RESULT_OK, Intent().putExtra(RESULT_AUTH_JSON, captured.toStorageJson()))
        finish()
    }

    override fun onDestroy() {
        popups.toList().forEach { it.first.dismiss() }
        if (this::browser.isInitialized) {
            browser.stopLoading()
            browser.clearCache(true)
            browser.destroy()
            CookieManager.getInstance().removeAllCookies(null)
            android.webkit.WebStorage.getInstance().deleteAllData()
        }
        super.onDestroy()
    }
}
