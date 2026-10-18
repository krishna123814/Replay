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
        fun toast(msg: String?) {
            main.post {
                try { android.widget.Toast.makeText(ctx.applicationContext, msg ?: "", android.widget.Toast.LENGTH_SHORT).show() } catch (e: Exception) { }
            }
        }

        @JavascriptInterface
        fun show(json: String?) {
            val o = try { JSONObject(json ?: "{}") } catch (e: Exception) { JSONObject() }
            main.post { handle(o) }
        }


        /** Listing page ka "⬇ Sab": page ke saare video links (JS se aate hain). */
        @JavascriptInterface
        fun bulk(json: String?) {
            val o = try { JSONObject(json ?: "{}") } catch (e: Exception) { JSONObject() }
            main.post { handleBulk(o) }
        }

        private fun handleBulk(o: JSONObject) {
            val arr = o.optJSONArray("items")
            val urls = ArrayList<String>()
            val names = ArrayList<String>()
            if (arr != null) for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val u = it.optString("u")
                if (u.isEmpty()) continue
                urls.add(u)
                names.add(it.optString("t").trim().ifEmpty { "video " + (urls.size) })
            }
            if (urls.isEmpty()) {
                android.widget.Toast.makeText(ctx.applicationContext, "Is page par video nahi mile", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            if (!ServerSync.enabled(ctx)) {
                DlUi.videoInfo(ctx, overlay,
                    "Sab videos ek saath server par bhejne ke liye pehle ⚙ Settings → ⬇ Downloads → ☁ Server settings me " +
                        "server (AIC ya HF), login aur folder chuno.")
                return
            }
            val page = o.optString("page")
            DlUi.bulkPick(ctx, overlay, names) { picked ->
                val app = ctx.applicationContext
                val ua = PopupLogin.cleanUa(app)
                val specs = picked.map { i ->
                    val cookie = try { android.webkit.CookieManager.getInstance().getCookie(urls[i]) ?: "" } catch (e: Exception) { "" }
                    val base = names[i].replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").replace(Regex("\\s+"), " ").trim().take(80)
                    DlSpec(urls[i], cookie, ua, page, base.ifEmpty { "video" }, "")
                }
                android.widget.Toast.makeText(app, "Quality / formats padh rahe hain… (10-40 sec)", android.widget.Toast.LENGTH_LONG).show()
                ServerSync.fetchHlsHeights(app, specs) { opts, audio ->
                    main.post { askQuality(specs, opts, audio) }
                }
            }
        }

        /** Sabhi videos ke liye ek common quality chuno, phir server ko bhejo. */
        private fun askQuality(specs: List<DlSpec>, opts: List<ServerSync.FormatOpt>, audio: Boolean) {
            val labels = ArrayList<String>()
            val heights = ArrayList<Int>()          // 0 = best, -1 = audio, >0 = us quality tak
            labels.add("⭐ Best available (har video ki sabse badi)"); heights.add(0)
            if (opts.isNotEmpty()) {
                for (o in opts) {
                    val extra = ArrayList<String>()
                    if (o.ext.isNotEmpty()) extra.add(o.ext)
                    if (o.fps > 30) extra.add(o.fps.toString() + "fps")
                    if (o.count < o.sampled) extra.add("kuch me hi")
                    labels.add(o.h.toString() + "p" + (if (extra.isEmpty()) "" else "  (" + extra.joinToString(", ") + ")"))
                    heights.add(o.h)
                }
            } else {
                for (h in intArrayOf(2160, 1440, 1080, 720, 480, 360, 240)) { labels.add(h.toString() + "p"); heights.add(h) }
            }
            if (audio) { labels.add("🎵 Sirf audio (mp3)"); heights.add(-1) }
            val title = "⬇ " + specs.size + " videos: kaunsi quality?" +
                (if (opts.isEmpty()) "\n(list nahi mili, standard options)" else "\n(jis video me wo quality na ho use sabse nazdeek milegi)")
            DlUi.qualityPick(ctx, overlay, title, labels) { idx ->
                ServerSync.sendBulk(ctx.applicationContext, specs, heights[idx])
            }
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
            val note = o.optString("note")
            val mans = ArrayList<String>()
            o.optJSONArray("manifests")?.let { m -> for (i in 0 until m.length()) mans.add(m.optString(i)) }
            if (mans.isEmpty()) { finish(items, page, title, note); return }
            // JS CORS ki wajah se playlist nahi padh payi: Kotlin se padho
            val app = ctx.applicationContext
            Thread {
                val ua = PopupLogin.cleanUa(app)
                val extra = ArrayList<Item>()
                for (m in mans.take(3)) {
                    val cookie = try { android.webkit.CookieManager.getInstance().getCookie(m) ?: "" } catch (e: Exception) { "" }
                    for ((label, url) in HlsDl.variantList(m, cookie, ua, page)) extra.add(Item(label, url, "hls", ""))
                    if (extra.isNotEmpty()) break
                }
                main.post { finish(items + extra, page, title, note) }
            }.start()
        }

        private fun finish(items: List<Item>, page: String, title: String, note: String) {
            if (items.isEmpty()) {
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
  var btns = new Map();           // element (video ya card link) -> button
  var SZ = 22;                    // icon size (px)
  var STYLE = 'position:fixed;z-index:2147483647;width:' + SZ + 'px;height:' + SZ + 'px;border-radius:' + (SZ / 2) + 'px;' +
    'background:rgba(41,98,255,.92);color:#fff;font:700 13px/' + SZ + 'px sans-serif;text-align:center;' +
    'box-shadow:0 1px 5px rgba(0,0,0,.5);touch-action:manipulation;user-select:none;display:none';

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

  function on() { try { return !!LionVid.enabled(); } catch (e) { return false; } }
  function toast(m) { try { LionVid.toast(m); } catch (e) {} }
  function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return u; } }
  function extOf(u) { var m = /\.(mp4|webm|mkv|mov|m4v)(\?|#|$)/i.exec(u); return m ? m[1].toLowerCase().replace('m4v', 'mp4') : ''; }

  // ── Card (listing page par thumbnail wala video link) pehchano ──
  var VIDRE = /(video|watch|movie|clip|view|play|embed|v\/|\/\d{4,})/i;
  function cardMedia(a) {
    try {
      if (!a.href || !/^https?:/i.test(a.href)) return null;
      var u = new URL(a.href);
      if (u.href.split('#')[0] === location.href.split('#')[0]) return null;
      if (/\.(jpe?g|png|gif|webp|svg|css|js)(\?|$)/i.test(u.pathname)) return null;
      if (!VIDRE.test(u.pathname)) return null;
      return a.querySelector('img,video,picture');
    } catch (e) { return null; }
  }

  function mkBtn(el, isCard) {
    var b = document.createElement('div');
    b.textContent = '\u2B07';
    b.setAttribute('style', STYLE);
    b.addEventListener('click', function (ev) {
      ev.preventDefault(); ev.stopPropagation();
      if (isCard) collectCard(el); else collect(el);
    }, true);
    b.addEventListener('touchstart', function (ev) { ev.stopPropagation(); }, true);
    (document.body || document.documentElement).appendChild(b);
    return b;
  }

  function show(b, r, left) {
    b.style.display = 'block';
    var x = left ? r.left + 6 : r.right - SZ - 6;
    b.style.left = Math.max(2, Math.min(innerWidth - SZ - 2, x)) + 'px';
    b.style.top = Math.max(2, Math.min(innerHeight - SZ - 2, r.top + 6)) + 'px';
  }

  function inView(r) { return r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth; }

  function place() {
    var enabled = on();
    var live = new Set();
    function handle(el, isCard) {
      var r = el.getBoundingClientRect();
      var vis = enabled && r.width > 100 && r.height > 60 && inView(r);
      var b = btns.get(el);
      if (!b && vis) { b = mkBtn(el, isCard); btns.set(el, b); }
      if (!b) return;
      live.add(el);
      if (!vis) { b.style.display = 'none'; return; }
      show(b, r, isCard);
    }
    var vids = document.getElementsByTagName('video');
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i], a = v.closest ? v.closest('a[href]') : null;
      if (a && cardMedia(a)) continue;          // card ke andar ka hover-preview: card ka icon hi kaafi
      handle(v, false);
    }
    var as = document.getElementsByTagName('a');
    for (var j = 0; j < as.length; j++) {
      var an = as[j], rr = an.getBoundingClientRect();
      if (rr.width < 100 || rr.height < 60 || !inView(rr)) continue;
      if (!cardMedia(an)) continue;
      handle(an, true);
    }
    btns.forEach(function (b, el) {
      if (!live.has(el) && !(enabled && inView(el.getBoundingClientRect()) && document.contains(el))) b.style.display = 'none';
      if (!document.contains(el)) { b.remove(); btns.delete(el); }
    });
  }

  var JUNK = /thumb|preview|sprite|heatmap|poster|trailer|teaser|storyboard|\/ads?\//i;

  function urlsFromText(h) {
    var out = [];
    try {
      h = h.replace(/\\\//g, '/').replace(/\\u0026/g, '&').replace(/&amp;/g, '&');
      var re = /https?:\/\/[^"'\s<>\\]+/gi, m;
      while ((m = re.exec(h)) && out.length < 60) if (/\.(m3u8|mp4|webm|mpd)(\?|#|$|\.)/i.test(m[0]) && out.indexOf(m[0]) < 0) out.push(m[0]);
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

  // ── asli kaam: pool (urls) se format/quality items banao ──
  function run(pool, ref, note, title, pageUrl) {
    var man = [], dir = [], hasMpd = false, tpl = [];
    pool.forEach(function (u) {
      if (!/^https?:/i.test(u)) return;
      if (u.indexOf('_TPL_') >= 0) {                 // template link: multi=WxH:144p,... se har quality ka link banao
        var mm = /multi=([^\/]+)/.exec(u);
        if (mm) mm[1].split(',').forEach(function (part) {
          var f = part.split(':'), lab = f[1] || ((/x(\d+)/.exec(f[0]) || [])[1] ? (/x(\d+)/.exec(f[0]))[1] + 'p' : '');
          if (!lab) return;
          var link = u.replace('_TPL_', lab);
          if (!tpl.some(function (t) { return t.url === link; })) tpl.push({ url: link, h: parseInt(lab, 10) || 0, lab: lab });
        });
        return;
      }
      if (/\.m3u8(\?|#|$)/i.test(u)) { if (man.indexOf(u) < 0 && !JUNK.test(u)) man.push(u); }
      else if (/\.mpd(\?|#|$)/i.test(u)) hasMpd = true;
      else if (extOf(u) && !JUNK.test(u) && !/\.(m4s)/i.test(u)) { if (!dir.some(function (d) { return d.url === u; })) dir.push({ url: u, h: 0, dur: 0 }); }
    });
    man = man.slice(0, 4); dir = dir.slice(0, 8);
    if (hasMpd && !man.length && !dir.length) note = 'DASH (.mpd) stream hai, abhi support nahi.';

    var items = [];
    var minDur = ref > 60 ? ref * 0.8 : 20;
    function done() {
      try { LionVid.show(JSON.stringify({ page: pageUrl, title: title || '', items: items, note: note, manifests: failed })); } catch (e) {}
    }

    var pm = Promise.all(dir.map(probe)).then(function (ds) {
      ds.filter(function (d) { return !d.dur || d.dur >= minDur; })
        .sort(function (a, b) { return b.h - a.h; })
        .forEach(function (d) {
          var ex = extOf(d.url) || 'mp4';
          var mh = d.h || (/(\d{3,4})p/i.exec(d.url) || [])[1] || 0;
          items.push({ label: ex.toUpperCase() + ' \u2022 ' + (mh ? mh + 'p' : 'original') + (d.dur ? ' \u2022 ' + fmtD(d.dur) : ''), url: d.url, kind: 'file', ext: ex });
        });
    });

    var variants = [], media = [], failed = [];
    tpl.forEach(function (t) { variants.push({ h: t.h, bw: 0, url: t.url }); });
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
      }).catch(function () { failed.push(m); });
    })).then(function () {
      var seenV = {};
      variants.sort(function (a, b) { return (b.h - a.h) || (b.bw - a.bw); });
      variants.forEach(function (x) {
        if (seenV[x.url]) return; seenV[x.url] = 1;
        items.push({ label: 'TS/MP4 \u2022 ' + (x.h ? x.h + 'p' : Math.round(x.bw / 1000) + ' kbps') + ' (HLS)', url: x.url, kind: 'hls', ext: '' });
      });
      if (!variants.length && !failed.length) media.forEach(function (x) {
        if (!x.dur || x.dur >= minDur)
          items.push({ label: 'HLS \u2022 best' + (x.dur ? ' \u2022 ' + fmtD(x.dur) : ''), url: x.url, kind: 'hls', ext: '' });
      });
    });

    Promise.all([pm, hm]).then(function () {
      if (!items.length && !note && ref && ref < 30) note = 'Ye sirf chhota preview hai (' + fmtD(ref) + '). Video ka apna page kholkar play karo, phir \u2B07 dabao.';
      done();
    }).catch(done);
  }

  // Video player par icon
  function collect(v) {
    toast('Link dhoondh rahe hain\u2026');
    var ref = v && isFinite(v.duration) ? v.duration : 0;
    var cur = v ? (v.currentSrc || v.src) : '';
    var note = '';
    var pool = [];
    if (cur && !/^blob:/i.test(cur)) pool.push(cur);
    if (v) { var ss = v.getElementsByTagName('source'); for (var i = 0; i < ss.length; i++) pool.push(abs(ss[i].src)); }
    pool = pool.concat(urlsFromText(document.documentElement.innerHTML));
    pool = pool.concat(seen.slice().reverse());
    try { performance.getEntriesByType('resource').reverse().forEach(function (e) { pool.push(e.name); }); } catch (e) {}
    run(pool, ref, note, document.title, location.href);
  }

  // Listing page ke card par icon: us video ka page peeche se load karke link nikalo
  function collectCard(a) {
    var href = a.href;
    toast('Video ka page load ho raha hai\u2026');
    fetch(href, { credentials: 'include' }).then(function (r) { return r.text(); }).then(function (t) {
      var ref = 0, m = /"duration"\s*:\s*"?(\d{2,6})"?/.exec(t) || /video:duration"\s+content="(\d+)/.exec(t);
      if (m) ref = +m[1];
      var tm = /<title[^>]*>([^<]*)<\/title>/i.exec(t);
      var title = tm ? tm[1].replace(/&amp;/g, '&').trim() : (a.textContent || '').trim();
      run(urlsFromText(t), ref, '', title, href);
    }).catch(function () {
      try { LionVid.show(JSON.stringify({ page: href, title: '', items: [], note: 'Is video ka page yahan se load nahi ho paya. Video kholkar play karo, phir \u2B07 dabao.' })); } catch (e) {}
    });
  }

  // ── Listing page par "⬇ Sab" button: page ke saare video-card links ek saath server ko ──
  var allBtn = null;
  function cardCount(max) {
    var n = 0, seenU = {}, as = document.getElementsByTagName('a');
    for (var i = 0; i < as.length && n < max; i++) {
      var a = as[i];
      if (!cardMedia(a)) continue;
      var u = a.href.split('#')[0];
      if (seenU[u]) continue;
      seenU[u] = 1; n++;
    }
    return n;
  }
  function collectAll() {
    var as = document.getElementsByTagName('a'), seenU = {}, items = [];
    for (var i = 0; i < as.length && items.length < 300; i++) {
      var a = as[i], m = cardMedia(a);
      if (!m) continue;
      var u = a.href.split('#')[0];
      if (seenU[u]) continue;
      seenU[u] = 1;
      var t = (a.getAttribute('title') || (m.getAttribute && m.getAttribute('alt')) || a.getAttribute('aria-label') || a.textContent || '').replace(/\s+/g, ' ').trim();
      if (!t) {
        var p = a.closest ? a.closest('li,article,div') : null;
        var h = p ? p.querySelector('[class*=title],h2,h3,h4') : null;
        if (h) t = (h.textContent || '').replace(/\s+/g, ' ').trim();
      }
      items.push({ u: u, t: t.slice(0, 120) });
    }
    if (!items.length) { toast('Is page par video nahi mile'); return; }
    try { LionVid.bulk(JSON.stringify({ page: location.href, title: document.title, items: items })); } catch (e) { toast('Sab-download khul nahi paya'); }
  }
  function updateAll() {
    var show = on() && cardCount(2) >= 2;
    if (show && !allBtn) {
      allBtn = document.createElement('div');
      allBtn.textContent = '\u2B07 Sab';
      allBtn.setAttribute('style', 'position:fixed;z-index:2147483647;right:10px;bottom:110px;padding:7px 13px;border-radius:18px;' +
        'background:rgba(41,98,255,.95);color:#fff;font:700 14px sans-serif;box-shadow:0 1px 6px rgba(0,0,0,.55);touch-action:manipulation;user-select:none;display:none');
      allBtn.addEventListener('click', function (ev) { ev.preventDefault(); ev.stopPropagation(); collectAll(); }, true);
      allBtn.addEventListener('touchstart', function (ev) { ev.stopPropagation(); }, true);
      (document.body || document.documentElement).appendChild(allBtn);
    }
    if (allBtn) allBtn.style.display = show ? 'block' : 'none';
  }

  window.__lionVid = { scan: place };
  setInterval(place, 700);
  setInterval(updateAll, 1500);
  addEventListener('scroll', place, { passive: true, capture: true });
  addEventListener('resize', place);
  place();
})();
""".trimIndent()
}
