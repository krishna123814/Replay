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

  // Network URLs record karo (resource-timing buffer listing pages par bhar jata hai)
  var seen = window.__lionUrls = window.__lionUrls || [];
  function rec(u) { try { u = new URL(u, location.href).href; if (/\.(m3u8|mpd|mp4|webm|mkv|mov|m4v)(\?|#|$)/i.test(u)) { var k = seen.indexOf(u); if (k >= 0) seen.splice(k, 1); seen.push(u); if (seen.length > 120) seen.shift(); } } catch (e) {} }
  try { performance.setResourceTimingBufferSize(2000); } catch (e) {}
  try {
    var of = window.fetch;
    if (of) window.fetch = function (a) { try { rec(typeof a === 'string' ? a : a.url); } catch (e) {} return of.apply(this, arguments); };
    var oo = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, u) { try { rec(u); } catch (e) {} return oo.apply(this, arguments); };
    new PerformanceObserver(function (l) { l.getEntries().forEach(function (e) { rec(e.name); }); }).observe({ entryTypes: ['resource'] });
  } catch (e) {}
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

  var JUNK = /thumb|preview|sprite|heatmap|poster|trailer|teaser|storyboard|\/ads?\//i;

  function htmlUrls() {
    var out = [];
    try {
      var h = document.documentElement.innerHTML.replace(/\\\//g, '/').replace(/\\u0026/g, '&').replace(/&amp;/g, '&');
      var re = /https?:\/\/[^"'\s<>\\]+?\.(?:m3u8|mp4|webm)(?:\?[^"'\s<>\\]*)?/gi, m;
      while ((m = re.exec(h)) && out.length < 40) if (out.indexOf(m[0]) < 0) out.push(m[0]);
    } catch (e) {}
    return out;
  }

  function probe(d) {
    return new Promise(function (res) {
      var t = document.createElement('video'), done = false;
      function fin() { if (done) return; done = true; try { t.removeAttribute('src'); t.load(); } catch (e) {} res(d); }
      t.muted = true; t.preload = 'metadata';
      t.onloadedmetadata = function () { d.dur = t.duration; d.h = t.videoHeight || d.h; fin(); };
      t.onerror = fin;
      setTimeout(fin, 5000);
      try { t.src = d.url; } catch (e) { fin(); }
    });
  }

  function fmtD(s) { s = Math.round(s); var h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60; return (h ? h + ':' + ('0' + m).slice(-2) : m) + ':' + ('0' + x).slice(-2); }

  function collect(v) {
    var ref = v && isFinite(v.duration) ? v.duration : 0;
    var cur = v ? (v.currentSrc || v.src) : '';
    var note = '';
    var pool = [];                       // priority order: current > html > hooks(latest first)
    if (cur && !/^blob:/i.test(cur)) pool.push(cur);
    if (v) { var ss = v.getElementsByTagName('source'); for (var i = 0; i < ss.length; i++) pool.push(abs(ss[i].src)); }
    pool = pool.concat(htmlUrls());
    pool = pool.concat(seen.slice().reverse());
    try { performance.getEntriesByType('resource').reverse().forEach(function (e) { pool.push(e.name); }); } catch (e) {}
    if (cur && /^blob:/i.test(cur)) note = 'Ye video blob stream hai; page ke andar se link dhoondh rahe hain.';

    var man = [], dir = [], hasMpd = false;
    pool.forEach(function (u) {
      if (!/^https?:/i.test(u)) return;
      if (/\.m3u8(\?|#|$)/i.test(u)) { if (man.indexOf(u) < 0 && !JUNK.test(u)) man.push(u); }
      else if (/\.mpd(\?|#|$)/i.test(u)) hasMpd = true;
      else if (extOf(u) && !JUNK.test(u) && !/\.(m4s)/i.test(u)) { if (!dir.some(function (d) { return d.url === u; })) dir.push({ url: u, h: 0, dur: 0 }); }
    });
    man = man.slice(0, 4); dir = dir.slice(0, 8);
    if (hasMpd && !man.length && !dir.length) note = 'DASH (.mpd) stream hai, abhi support nahi.';

    var items = [];
    function done() {
      try { LionVid.show(JSON.stringify({ page: location.href, title: document.title || '', items: items, note: note })); } catch (e) {}
    }

    var pm = Promise.all(dir.map(probe)).then(function (ds) {
      var minDur = ref > 60 ? ref * 0.8 : 20;
      ds.filter(function (d) { return !d.dur || d.dur >= minDur; })
        .sort(function (a, b) { return b.h - a.h; })
        .forEach(function (d) {
          var ex = extOf(d.url) || 'mp4';
          var mh = d.h || (/(\d{3,4})p/i.exec(d.url) || [])[1] || 0;
          items.push({ label: ex.toUpperCase() + ' \u2022 ' + (mh ? mh + 'p' : 'original') + (d.dur ? ' \u2022 ' + fmtD(d.dur) : ''), url: d.url, kind: 'file', ext: ex });
        });
    });

    var variants = [], media = [];
    var hm = Promise.all(man.map(function (m) {
      return fetch(m).then(function (r) { return r.text(); }).then(function (t) {
        var lines = t.split(/\r?\n/), found = false;
        for (var i = 0; i < lines.length; i++) {
          if (lines[i].indexOf('#EXT-X-STREAM-INF') === 0) {
            found = true;
            var res = /RESOLUTION=(\d+)x(\d+)/.exec(lines[i]), bw = /BANDWIDTH=(\d+)/.exec(lines[i]);
            var j = i + 1; while (j < lines.length && (!lines[j].trim() || lines[j][0] === '#')) j++;
            if (j < lines.length) variants.push({ h: res ? +res[2] : 0, bw: bw ? +bw[1] : 0, url: new URL(lines[j].trim(), m).href });
          }
        }
        if (!found && t.indexOf('#EXTINF') >= 0) {
          var tot = 0, re = /#EXTINF:([\d.]+)/g, mm; while ((mm = re.exec(t))) tot += parseFloat(mm[1]);
          media.push({ url: m, dur: tot });
        }
      }).catch(function () { media.push({ url: m, dur: 0 }); });
    })).then(function () {
      var seenV = {};
      variants.sort(function (a, b) { return (b.h - a.h) || (b.bw - a.bw); });
      variants.forEach(function (x) {
        if (seenV[x.url]) return; seenV[x.url] = 1;
        items.push({ label: 'TS/MP4 \u2022 ' + (x.h ? x.h + 'p' : Math.round(x.bw / 1000) + ' kbps') + ' (HLS)', url: x.url, kind: 'hls', ext: '' });
      });
      if (!variants.length) media.forEach(function (x) {
        if (!x.dur || x.dur >= (ref > 60 ? ref * 0.8 : 20))
          items.push({ label: 'HLS \u2022 best' + (x.dur ? ' \u2022 ' + fmtD(x.dur) : ''), url: x.url, kind: 'hls', ext: '' });
      });
    });

    Promise.all([pm, hm]).then(function () {
      if (!items.length && ref && ref < 30) note = 'Ye sirf chhota preview hai (' + fmtD(ref) + '). Video ka apna page kholkar play karo, phir ⬇ dabao.';
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
