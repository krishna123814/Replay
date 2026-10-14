package com.example.simplebrowser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class BrowserActivity : AppCompatActivity() {


    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var btnBack: Button
    private lateinit var btnForward: Button

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        web = findViewById(R.id.web)
        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (!urlBar.hasFocus()) urlBar.setText(url)
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
                updateNavButtons()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val scheme = request.url.scheme ?: return false
                if (scheme == "http" || scheme == "https") return false
                // tel:, mailto:, intent: jaise links ko dusri app me kholo
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (_: Exception) {
                }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
            }
        }

        btnBack.setOnClickListener { if (web.canGoBack()) web.goBack() }
        btnForward.setOnClickListener { if (web.canGoForward()) web.goForward() }
        findViewById<Button>(R.id.btnReload).setOnClickListener { web.reload() }
        findViewById<Button>(R.id.btnGo).setOnClickListener { goToInput() }
        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                goToInput()
                true
            } else false
        }

        // Phone ka back button: pehle page me peeche, nahi to app band
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) {
                    web.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState == null) {
            web.loadUrl(AppConfig.BROWSER_HOME)
        } else {
            web.restoreState(savedInstanceState)
        }
        updateNavButtons()
    }

    private fun goToInput() {
        web.loadUrl(toUrl(urlBar.text.toString()))
        urlBar.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    /** Typed text ko URL ya Google search me badalta hai. */
    private fun toUrl(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return AppConfig.BROWSER_HOME
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        val ipPort = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$")
        if (ipPort.matches(t)) return "http://$t"          // IP:port aam taur par http hota hai
        val looksLikeHost = !t.contains(" ") && (t.contains(".") || t.startsWith("localhost"))
        return if (looksLikeHost) "https://$t"
        else "https://www.google.com/search?q=" + Uri.encode(t)
    }

    private fun updateNavButtons() {
        btnBack.alpha = if (web.canGoBack()) 1f else 0.35f
        btnForward.alpha = if (web.canGoForward()) 1f else 0.35f
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
