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
 *  - Icon dabane par: video ka link, resolution, aur HLS/DASH me jo quality list mili wo dikhti hai.
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
            val text = try {
                format(JSONObject(json ?: "{}"))
            } catch (e: Exception) {
                "Info padh nahi payi"
            }
            main.post { DlUi.videoInfo(ctx, overlay, text) }
        }

        private fun format(o: JSONObject): String {
            val sb = StringBuilder()
            val page = o.optString("page")
            if (page.isNotEmpty()) sb.append("Page: ").append(page).append("\n\n")
            val w = o.optInt("w"); val h = o.optInt("h")
            if (w > 0 && h > 0) sb.append("Abhi chal rahi: ${w}x$h\n")
            val dur = o.optDouble("dur", 0.0)
            if (dur > 0 && !dur.isInfinite()) sb.append("Length: ").append(fmtDur(dur.toLong())).append("\n")
            sb.append("\n")
            val q = o.optJSONArray("quals")
            if (q != null && q.length() > 0) {
                sb.append("Quality (stream se):\n")
                for (i in 0 until q.length()) sb.append("  • ").append(q.optString(i)).append("\n")
                sb.append("\n")
            } else {
                sb.append("Quality list is stream me nahi mili (single file ho sakti hai).\n\n")
            }
            val l = o.optJSONArray("links")
            if (l != null && l.length() > 0) {
                sb.append("Mile links:\n")
                for (i in 0 until l.length()) sb.append("  ").append(i + 1).append(". ").append(l.optString(i)).append("\n")
            } else {
                sb.append("Koi seedha link nahi mila (blob/protected stream ho sakti hai).\n")
            }
            sb.append("\n(Download abhi band hai, agle step me aayega)")
            return sb.toString()
        }

        private fun fmtDur(s: Long): String {
            val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
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

  function mediaLinks(v) {
    var out = [];
    function add(u) { if (u && out.indexOf(u) < 0) out.push(u); }
    if (v) {
      add(v.currentSrc || v.src);
      var ss = v.getElementsByTagName('source');
      for (var i = 0; i < ss.length; i++) add(abs(ss[i].src));
    }
    try {
      performance.getEntriesByType('resource').forEach(function (e) {
        var n = e.name;
        if (/\.(mp4|webm|m3u8|mpd|mkv|mov|m4v)(\?|#|${'$'})/i.test(n)) add(n);
      });
    } catch (e) {}
    return out;
  }

  function parseM3u8(txt) {
    var q = [], lines = txt.split(/\r?\n/);
    for (var i = 0; i < lines.length; i++) {
      if (lines[i].indexOf('#EXT-X-STREAM-INF') === 0) {
        var m = /RESOLUTION=(\d+)x(\d+)/.exec(lines[i]);
        var bw = /BANDWIDTH=(\d+)/.exec(lines[i]);
        if (m) q.push(m[2] + 'p (' + m[1] + 'x' + m[2] + ')' + (bw ? ' ~' + Math.round(bw[1] / 1000) + ' kbps' : ''));
      }
    }
    return q;
  }
  function parseMpd(txt) {
    var q = [], re = /<Representation[^>]*?(?:width="(\d+)"[^>]*?height="(\d+)"|height="(\d+)"[^>]*?width="(\d+)")/g, m;
    while ((m = re.exec(txt))) {
      var w = m[1] || m[4], h = m[2] || m[3];
      var s = h + 'p (' + w + 'x' + h + ')';
      if (q.indexOf(s) < 0) q.push(s);
    }
    return q;
  }

  function collect(v) {
    var links = mediaLinks(v);
    var info = { page: location.href, w: v ? v.videoWidth : 0, h: v ? v.videoHeight : 0,
                 dur: v ? v.duration : 0, links: links, quals: [] };
    var manifest = null;
    for (var i = 0; i < links.length; i++) if (/\.(m3u8|mpd)(\?|#|${'$'})/i.test(links[i])) { manifest = links[i]; break; }
    function done() { try { LionVid.show(JSON.stringify(info)); } catch (e) {} }
    if (!manifest) { done(); return; }
    fetch(manifest).then(function (r) { return r.text(); }).then(function (t) {
      info.quals = /\.mpd/i.test(manifest) ? parseMpd(t) : parseM3u8(t);
      done();
    }).catch(done);
  }

  window.__lionVid = { scan: place };
  setInterval(place, 700);
  addEventListener('scroll', place, { passive: true, capture: true });
  addEventListener('resize', place);
  place();
})();
""".trimIndent()
}
