package com.example.simplebrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * Snaptube jaisa video detect (STEP 1: sirf dikhana, download nahi).
 *  - Page me <video> milte hi uske upar chhota ⬇ icon aata hai.
 *  - Icon dabane par: format/quality ki list aati hai, chun-ne par download shuru (direct file = download engine, HLS = HlsDl).
 *  - Settings > "Video par download icon dikhao" se on/off (LionVid.enabled() har baar check hota hai).
 */
object VideoDetect {

    const val BRIDGE_NAME = "LionVid"

    private val main = Handler(Looper.getMainLooper())

    /** Har page par (onPageStarted + onPageFinished) chalao. Dobara chalne se kuch double nahi hota. */
    fun inject(web: WebView?) {
        web?.evaluateJavascript(JS, null)
    }

    class Bridge(private val ctx: Context, private val overlay: Boolean) {

        @JavascriptInterface
        fun enabled(): Boolean = DlSettings.vidIcon(ctx)

        @JavascriptInterface
        fun show(json: String?) {
            val o = try { JSONObject(json ?: "{}") } catch (e: Exception) { JSONObject() }
            main.post { handle(o) }
        }

        private class Item(val label: String, val url: String, val kind: String, val ext: String)

        private fun handle(o: JSONObject) {
            val items = ArrayList<Item>()
            val arr = o.optJSONArray("items")
            if (arr != null) for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                items.add(Item(it.optString("label"), it.optString("url"), it.optString("kind"), it.optString("ext")))
            }
            val page = o.optString("page")
            val title = o.optString("title")
            if (items.isEmpty()) {
                val note = o.optString("note")
                DlUi.videoInfo(ctx, overlay,
                    (if (note.isNotEmpty()) note else "Is video ka seedha link nahi mila.") +
                        "\n\n(blob/protected stream, YouTube jaisi site ya DRM ho sakti hai)")
                return
            }
            DlUi.videoPick(ctx, overlay, items.map { it.label }) { idx -> start(items[idx], page, title) }
        }

        private fun start(it: Item, page: String, title: String) {
            val app = ctx.applicationContext
            val ua = PopupLogin.cleanUa(app)
            val cookie = try { android.webkit.CookieManager.getInstance().getCookie(it.url) ?: "" } catch (e: Exception) { "" }
            var base = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").replace(Regex("\\s+"), " ").trim().take(60)
            if (base.isEmpty()) base = "video"
            val q = Regex("(\\d{3,4})p").find(it.label)?.groupValues?.get(1)
            if (q != null) base += "_" + q + "p"
            if (it.kind == "hls") {
                HlsDl.start(app, it.url, base, cookie, ua, page)
            } else {
                val ext = it.ext.ifEmpty { "mp4" }
                val mime = when (ext) { "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; "mov" -> "video/quicktime"; else -> "video/mp4" }
                val ok = Downloader.enqueueNow(app, DlSpec(it.url, cookie, ua, page, "$base.$ext", mime), false)
                android.widget.Toast.makeText(app,
                    if (ok) "Download queue me: $base.$ext\n(⚙ Settings → 📥 Download queue)" else "Download nahi ho paya",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // Page me chalne wali script. Bridge ka naam: LionVid
    private val JS = """
(function () {
  if (window.__lionVid) { try { window.__lionVid.scan(); } catch (e) {} return; }
  var btns = new Map();           // video element -> button
  var STYLE = 'position:fixed;z-index:2147483647;width:40px;height:40px;border-radius:20px;' +
    'background:rgba(41,98,255,.92);color:#fff;font:700 20px/40px sans-serif;text-align:center;' +
    'box-shadow:0 2px 8px rgba(0,0,0,.5);touch-action:manipulation;user-select:none;display:none';

  function on() { try { return !!LionVid.enabled(); } catch (e) { return false; } }

  function mkBtn(v) {
    var b = document.createElement('div');
    b.textContent = '\u2B07';
    b.setAttribute('style', STYLE);
    b.addEventListener('click', function (ev) {
      ev.preventDefault(); ev.stopPropagation(); collect(v);
    }, true);
    b.addEventListener('touchstart', function (ev) { ev.stopPropagation(); }, true);
    (document.body || document.documentElement).appendChild(b);
    return b;
  }

  function place() {
    var enabled = on();
    var vids = document.getElementsByTagName('video');
    var seen = new Set();
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i]; seen.add(v);
      var r = v.getBoundingClientRect();
      var visible = enabled && r.width > 120 && r.height > 80 &&
        r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth;
      var b = btns.get(v);
      if (!b && visible) { b = mkBtn(v); btns.set(v, b); }
      if (!b) continue;
      if (!visible) { b.style.display = 'none'; continue; }
      b.style.display = 'block';
      b.style.left = Math.max(4, Math.min(innerWidth - 44, r.right - 48)) + 'px';
      b.style.top = Math.max(4, Math.min(innerHeight - 44, r.top + 8)) + 'px';
    }
    btns.forEach(function (b, v) {
      if (!seen.has(v) || !document.contains(v)) { b.remove(); btns.delete(v); }
    });
  }

  function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return u; } }
  function extOf(u) { var m = /\.(mp4|webm|mkv|mov|m4v)(\?|#|$)/i.exec(u); return m ? m[1].toLowerCase().replace('m4v', 'mp4') : ''; }

  function collect(v) {
    var direct = [], manifests = [], note = '';
    function addDirect(u, h) {
      if (!u || !/^https?:/i.test(u)) return;
      for (var i = 0; i < direct.length; i++) if (direct[i].url === u) return;
      direct.push({ url: u, h: h || 0 });
    }
    function addMan(u) { if (u && manifests.indexOf(u) < 0) manifests.push(u); }
    var cur = v ? (v.currentSrc || v.src) : '';
    if (cur) {
      if (/^blob:/i.test(cur)) note = 'Ye video blob stream hai (site chhupa ke chalati hai).';
      else if (/\.m3u8/i.test(cur)) addMan(cur);
      else if (/\.mpd/i.test(cur)) note = 'DASH (.mpd) stream hai, abhi support nahi.';
      else addDirect(cur, v.videoHeight);
    }
    if (v) { var ss = v.getElementsByTagName('source'); for (var i = 0; i < ss.length; i++) { var u = abs(ss[i].src); if (/\.m3u8/i.test(u)) addMan(u); else addDirect(u, 0); } }
    try {
      performance.getEntriesByType('resource').forEach(function (e) {
        var n = e.name;
        if (/\.m3u8(\?|#|$)/i.test(n)) addMan(n);
        else if (/\.mpd(\?|#|$)/i.test(n) && !note) note = 'DASH (.mpd) stream hai, abhi support nahi.';
      });
      if (!manifests.length) performance.getEntriesByType('resource').forEach(function (e) {
        if (extOf(e.name) && direct.length < 6) addDirect(e.name, 0);
      });
    } catch (e) {}

    var items = [];
    direct.forEach(function (d) {
      var ex = extOf(d.url) || 'mp4';
      items.push({ label: ex.toUpperCase() + (d.h ? ' \u2022 ' + d.h + 'p' : ' \u2022 original quality'), url: d.url, kind: 'file', ext: ex });
    });
    var info = { page: location.href, title: document.title || '', items: items, note: note };
    var pending = manifests.length;
    function done() { try { LionVid.show(JSON.stringify(info)); } catch (e) {} }
    if (!pending) { done(); return; }
    manifests.slice(0, 3).forEach(function (m) {
      function fin() { if (--pending <= 0 || false) done(); }
      fetch(m).then(function (r) { return r.text(); }).then(function (t) {
        var lines = t.split(/\r?\n/), vs = [];
        for (var i = 0; i < lines.length; i++) {
          if (lines[i].indexOf('#EXT-X-STREAM-INF') === 0) {
            var res = /RESOLUTION=(\d+)x(\d+)/.exec(lines[i]), bw = /BANDWIDTH=(\d+)/.exec(lines[i]);
            var j = i + 1; while (j < lines.length && (!lines[j].trim() || lines[j][0] === '#')) j++;
            if (j < lines.length) vs.push({ h: res ? +res[2] : 0, bw: bw ? +bw[1] : 0, url: new URL(lines[j].trim(), m).href });
          }
        }
        if (vs.length) {
          vs.sort(function (a, b) { return (b.h - a.h) || (b.bw - a.bw); });
          vs.forEach(function (x) { items.push({ label: 'TS/MP4 \u2022 ' + (x.h ? x.h + 'p' : Math.round(x.bw / 1000) + ' kbps') + ' (HLS)', url: x.url, kind: 'hls', ext: '' }); });
        } else items.push({ label: 'HLS \u2022 auto quality', url: m, kind: 'hls', ext: '' });
        fin();
      }).catch(function () { items.push({ label: 'HLS \u2022 best quality', url: m, kind: 'hls', ext: '' }); fin(); });
    });
  }

  window.__lionVid = { scan: place };
  setInterval(place, 700);
  addEventListener('scroll', place, { passive: true, capture: true });
  addEventListener('resize', place);
  place();
})();
""".trimIndent()
}
