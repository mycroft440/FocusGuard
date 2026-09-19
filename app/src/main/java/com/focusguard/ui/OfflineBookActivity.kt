package com.focusguard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.webkit.WebViewAssetLoader
import com.focusguard.R

class OfflineBookActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var book: OfflineBook
    private lateinit var bookAssetDirectory: String

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        book = selectedBook()
        bookAssetDirectory = book.assetDirectory
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_offline_book)

        val root = findViewById<android.view.View>(R.id.offlineBookRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val safeArea = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = safeArea.left,
                top = safeArea.top,
                right = safeArea.right,
                bottom = safeArea.bottom
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)

        webView = findViewById(R.id.bookWebView)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = !request.url.isOfflineBookAsset()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (book == OfflineBook.EASYPEASY) {
                    if (webView.url.isEasyPeasyLanguageSelector()) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    } else {
                        webView.loadUrl(easyPeasyLanguageSelectorUrl())
                    }
                    return
                }

                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl(bookUrl())
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        webView.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }

    private fun Uri.isOfflineBookAsset(): Boolean =
        scheme == "https" &&
            host == WebViewAssetLoader.DEFAULT_DOMAIN &&
            path?.startsWith("/assets/$bookAssetDirectory/") == true

    private fun String?.isEasyPeasyLanguageSelector(): Boolean {
        if (this == null) return false
        val uri = Uri.parse(this)
        return uri.scheme == "https" &&
            uri.host == WebViewAssetLoader.DEFAULT_DOMAIN &&
            uri.path == "/assets/${OfflineBook.EASYPEASY.assetDirectory}/$EASYPEASY_LANGUAGE_SELECTOR"
    }

    private fun selectedBook(): OfflineBook =
        OfflineBook.values().firstOrNull {
            it.name == intent.getStringExtra(EXTRA_BOOK)
        } ?: OfflineBook.EASYPEASY

    private fun bookUrl(): String =
        if (book == OfflineBook.EASYPEASY) {
            easyPeasyLanguageSelectorUrl()
        } else {
            "https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/$bookAssetDirectory/index.html"
        }

    private fun easyPeasyLanguageSelectorUrl(): String =
        "https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/${OfflineBook.EASYPEASY.assetDirectory}/$EASYPEASY_LANGUAGE_SELECTOR"

    companion object {
        private const val EXTRA_BOOK = "offline_book"
        private const val EASYPEASY_LANGUAGE_SELECTOR = "language-selector.html"

        fun createIntent(context: Context, book: OfflineBook): Intent =
            Intent(context, OfflineBookActivity::class.java)
                .putExtra(EXTRA_BOOK, book.name)
    }

    enum class OfflineBook(val assetDirectory: String) {
        CREATOR_INSTRUCTIONS("creator-instructions"),
        EASYPEASY("easypeasy")
    }
}
