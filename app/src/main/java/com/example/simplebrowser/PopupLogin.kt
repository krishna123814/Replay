package com.example.simplebrowser

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Message
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView

/**
 * window.open / "Google se login" popup ko asli alag WebView me kholta hai (window.opener bana rehta hai),
 * taaki login ke baad result asli page ko mile aur blank white page na aaye.
 * Popup khud band (window.close) hone par dialog bhi band ho jata hai.
 */
object PopupLogin {

    /** "; wv" aur "Version/4.0" hatao: Google/kuch sites WebView ko login se rok deti hain. */
    fun cleanUa(ctx: Context): String =
        WebSettings.getDefaultUserAgent(ctx).replace("; wv", "").replace("Version/4.0 ", "")

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    @Suppress("SetJavaScriptEnabled")
    fun open(ctx: Context, overlay: Boolean, resultMsg: Message?): Boolean {
        val msg = resultMsg ?: return false
        val transport = msg.obj as? WebView.WebViewTransport ?: return false
        return try {
            val themed = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            val pw = WebView(themed)
            pw.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = cleanUa(ctx)
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(pw, true)

            val dlg = Dialog(themed)
            val frame = FrameLayout(themed)
            frame.setBackgroundColor(Color.WHITE)
            frame.addView(
                pw,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            val close = TextView(themed)
            close.text = "✕"
            close.textSize = 20f
            close.setTextColor(Color.WHITE)
            close.setBackgroundColor(Color.argb(170, 30, 34, 45))
            close.gravity = Gravity.CENTER
            close.setOnClickListener { dlg.dismiss() }
            val d = ctx.resources.displayMetrics.density
            frame.addView(close, FrameLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt(), Gravity.TOP or Gravity.END))
            dlg.setContentView(frame)
            dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            if (overlay) dlg.window?.setType(overlayType())

            pw.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean = false
                override fun onPageFinished(v: WebView?, url: String?) {
                    CookieManager.getInstance().flush()
                }
            }
            pw.webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView?) {
                    try { dlg.dismiss() } catch (e: Exception) { }
                }

                override fun onCreateWindow(
                    view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
                ): Boolean = open(ctx, overlay, resultMsg)
            }
            dlg.setOnDismissListener {
                CookieManager.getInstance().flush()
                try { pw.destroy() } catch (e: Exception) { }
            }
            transport.webView = pw
            msg.sendToTarget()
            dlg.show()
            true
        } catch (e: Exception) {
            false
        }
    }
}
