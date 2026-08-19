package com.focusguard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.webkit.WebViewAssetLoader
import com.focusguard.R

class OfflineBookActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var bookAssetDirectory: String
    private var pendingBookExportHtml: String? = null

    private val createBookDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
            val html = pendingBookExportHtml
            pendingBookExportHtml = null
            if (uri == null || html == null) return@registerForActivityResult

            runCatching {
                contentResolver.openOutputStream(uri)?.use { output ->
                    output.writer(Charsets.UTF_8).buffered().use { writer ->
                        writer.write(html)
                    }
                } ?: error("Unable to open destination file")
            }.onSuccess {
                Toast.makeText(this, "Livro HTML exportado com sucesso.", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, "Não foi possível exportar o livro.", Toast.LENGTH_LONG).show()
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookAssetDirectory = selectedBook().assetDirectory
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

        if (bookAssetDirectory == OfflineBook.EASYPEASY.assetDirectory) {
            webView.addJavascriptInterface(BookEditorBridge(), BOOK_EDITOR_BRIDGE)
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
            removeJavascriptInterface(BOOK_EDITOR_BRIDGE)
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        pendingBookExportHtml = null
        super.onDestroy()
    }

    private inner class BookEditorBridge {
        @JavascriptInterface
        fun exportHtml(fileName: String, html: String) {
            if (html.isBlank()) return
            runOnUiThread {
                pendingBookExportHtml = html
                createBookDocumentLauncher.launch(normalizeHtmlFileName(fileName))
            }
        }
    }

    private fun normalizeHtmlFileName(fileName: String): String {
        val sanitized = fileName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(120)
            .ifBlank { "EasyPeasy-editado.html" }
        return if (sanitized.endsWith(".html", ignoreCase = true)) {
            sanitized
        } else {
            "$sanitized.html"
        }
    }

    private fun Uri.isOfflineBookAsset(): Boolean =
        scheme == "https" &&
            host == WebViewAssetLoader.DEFAULT_DOMAIN &&
            path?.startsWith("/assets/$bookAssetDirectory/") == true

    private fun selectedBook(): OfflineBook =
        OfflineBook.values().firstOrNull {
            it.name == intent.getStringExtra(EXTRA_BOOK)
        } ?: OfflineBook.EASYPEASY

    private fun bookUrl(): String =
        "https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/$bookAssetDirectory/index.html"

    companion object {
        private const val EXTRA_BOOK = "offline_book"
        private const val BOOK_EDITOR_BRIDGE = "FocusGuardBookExporter"

        fun createIntent(context: Context, book: OfflineBook): Intent =
            Intent(context, OfflineBookActivity::class.java)
                .putExtra(EXTRA_BOOK, book.name)
    }

    enum class OfflineBook(val assetDirectory: String) {
        CREATOR_INSTRUCTIONS("creator-instructions"),
        EASYPEASY("easypeasy")
    }
}
