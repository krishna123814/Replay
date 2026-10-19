package com.example.simplebrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * <select> dropdown fix: floating (overlay) window me native popup nahi khulta.
 * Tap pakad ke apni list dikhate hain; chunne par value set + input/change event.
 *
 * Ye JS HAR frame me chalti hai (iframe, cross-origin iframe bhi) — document-start script se
 * (androidx.webkit). Purana evaluateJavascript wala tareeka fallback hai (sirf main frame).
 * Shadow DOM ke select bhi pakde jaate hain (composedPath).
 * Chuni hui value wapas usi frame ko postMessage se jaati hai jisme select hai.
 */
object SelectFix {
    const val BRIDGE = "LionSel"

    val js: String = """
(function () {
  if (window.__lionSel) return;
  window.__lionSel = 1;
  var tx = 0, ty = 0, moved = false, lastOpen = 0, cur = null, tok = '';
  function pathOf(ev) {
    try { if (ev.composedPath) { var p = ev.composedPath(); if (p && p.length) return p; } } catch (e) {}
    var a = [], n = ev.target;
    while (n) { a.push(n); n = n.parentNode || n.host; }
    return a;
  }
  function selOf(ev) {
    var p = pathOf(ev);
    for (var i = 0; i < p.length; i++) {
      var n = p[i];
      if (n && n.tagName === 'SELECT') {
        if (n.disabled || n.multiple || n.size > 1) return null;
        return n;
      }
    }
    return null;
  }
  function label(s) {
    var l = s.getAttribute('aria-label') || '';
    if (!l && s.labels && s.labels[0]) l = s.labels[0].textContent || '';
    if (!l) l = s.name || s.id || '';
    return l.replace(/\s+/g, ' ').trim();
  }
  function openSel(s) {
    var now = Date.now();
    if (now - lastOpen < 500) return;
    lastOpen = now;
    var opts = [];
    for (var i = 0; i < s.options.length; i++) {
      var o = s.options[i];
      opts.push({ t: (o.text || o.label || o.value || '').replace(/\s+/g, ' ').trim(), d: (o.disabled ? 1 : 0) });
    }
    cur = s;
    tok = Math.random().toString(36).slice(2, 12) + Date.now().toString(36);
    try { s.blur(); } catch (e) {}
    try { LionSel.pick(JSON.stringify({ i: s.selectedIndex, o: opts, title: label(s), tok: tok })); } catch (e) {}
  }
  window.addEventListener('message', function (ev) {
    var d = ev.data;
    if (!d || typeof d !== 'object' || !tok || d.__lionSelTok !== tok || !cur) return;
    var i = d.idx;
    tok = '';
    if (typeof i !== 'number' || i < 0 || i >= cur.options.length) return;
    cur.selectedIndex = i;
    cur.dispatchEvent(new Event('input', { bubbles: true }));
    cur.dispatchEvent(new Event('change', { bubbles: true }));
  }, false);
  document.addEventListener('touchstart', function (ev) {
    var t = ev.touches && ev.touches[0];
    if (t) { tx = t.clientX; ty = t.clientY; moved = false; }
  }, { capture: true, passive: true });
  document.addEventListener('touchmove', function (ev) {
    var t = ev.touches && ev.touches[0];
    if (t && (Math.abs(t.clientX - tx) > 10 || Math.abs(t.clientY - ty) > 10)) moved = true;
  }, { capture: true, passive: true });
  document.addEventListener('touchend', function (ev) {
    var s = selOf(ev);
    if (!s || moved) return;
    if (ev.cancelable) ev.preventDefault();
    openSel(s);
  }, { capture: true, passive: false });
  ['mousedown', 'click'].forEach(function (n) {
    document.addEventListener(n, function (ev) {
      var s = selOf(ev);
      if (!s) return;
      ev.preventDefault();
      ev.stopPropagation();
      if (n === 'click') openSel(s);
    }, true);
  });
})();
""".trimIndent()

    class Bridge(private val ctx: Context, private val overlay: Boolean, private val web: WebView) {
        private val ui = Handler(Looper.getMainLooper())

        @JavascriptInterface
        fun pick(json: String?) {
            ui.post {
                try {
                    val o = JSONObject(json ?: return@post)
                    val tok = o.optString("tok")
                    if (!Regex("[a-z0-9]{1,40}").matches(tok)) return@post
                    val arr = o.getJSONArray("o")
                    val labels = ArrayList<String>()
                    val off = HashSet<Int>()
                    for (i in 0 until arr.length()) {
                        val e = arr.getJSONObject(i)
                        labels.add(e.optString("t"))
                        if (e.optInt("d", 0) == 1) off.add(i)
                    }
                    if (labels.isEmpty()) return@post
                    DlUi.selectPick(ctx, overlay, o.optString("title"), labels, o.optInt("i", -1)) { idx ->
                        if (idx !in off) {
                            // main frame se saare (nested, cross-origin bhi) frames ko message bhejo; jiske paas tok hai wo apply karega
                            val code = "(function(){var m={__lionSelTok:'" + tok + "',idx:" + idx + "};" +
                                "function b(w,d){try{w.postMessage(m,'*');}catch(e){}" +
                                "if(d>8)return;try{for(var i=0;i<w.frames.length;i++)b(w.frames[i],d+1);}catch(e){}}" +
                                "b(window,0);})()"
                            web.evaluateJavascript(code, null)
                        }
                    }
                } catch (e: Exception) {
                }
            }
        }
    }

    /** WebView banate hi ek baar: bridge + (jahan chale) document-start script. */
    fun install(web: WebView, ctx: Context, overlay: Boolean) {
        web.addJavascriptInterface(Bridge(ctx, overlay, web), BRIDGE)
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(web, js, setOf("*"))
            }
        } catch (e: Throwable) {
        }
    }

    /** Fallback: page ke start / finish par main frame me (guard hone se double nahi chalta). */
    fun inject(web: WebView?) {
        try { web?.evaluateJavascript(js, null) } catch (e: Throwable) { }
    }
}
