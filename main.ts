// binance-proxy.ts  —  Render par chalne wala Binance proxy
//
// Kya badla (bandwidth bachane ke liye):
//   1. ON-DEMAND SNAPSHOT  →  GET /snapshot?strikes=20[&expiries=N]
//      Proxy Binance ke 3 WebSocket (option mark, option trade, spot) khud sunta hai
//      aur latest data apni memory mein rakhta hai. Binance → Render ka data Render
//      ke liye INBOUND hai (bill nahi hota). HF Space jab chahe /snapshot maangti
//      hai aur sirf ATM ± N strikes ka COMPACT + GZIP data wapas milta hai.
//      Jab koi /snapshot nahi maangta (IDLE_STOP_SEC), Binance WS band ho jaate
//      hain — tab Render ko koi kaam nahi, aur free instance so sakta hai.
//   2. FILTERED REST  →  /eapi/v1/exchangeInfo, /eapi/v1/ticker, /eapi/v1/mark
//      (bina `symbol` param ke) ab sirf BTC ke contracts (aur exchangeInfo mein
//      sirf zaroori fields) wapas karte hain, chhote server-side cache ke saath.
//   3. GZIP  →  saare bade JSON responses gzip hote hain (agar client accept kare).
//   4. LEGACY  →  purane /ws/mark, /ws/trade, /ws/spot relay abhi bhi maujood hain
//      (rollback ke liye) — par ye poora data forward karte hain, bandwidth zyada.
//
// Env vars (sab optional):
//   PORT             (Render khud deta hai)
//   IDLE_STOP_SEC    default 60   — itni der /snapshot na aaye to Binance WS band
//   TICKER_TTL_SEC   default 5    — 24h ticker (OI/volume/chg%) refresh gap
//   FILTER_REST      default true — "false" karne par REST ka filtering band

// ── Config ────────────────────────────────────────────────────────────────
const PORT = Number(Deno.env.get("PORT") ?? 8000);
const IDLE_STOP_MS = Number(Deno.env.get("IDLE_STOP_SEC") ?? 60) * 1000;
const TICKER_TTL_MS = Number(Deno.env.get("TICKER_TTL_SEC") ?? 5) * 1000;
const FILTER_REST = (Deno.env.get("FILTER_REST") ?? "true").toLowerCase() !== "false";

const SYMBOL_PREFIX = "BTC-";          // sirf BTC options (app.py bhi sirf BTCUSDT use karta hai)
const DEFAULT_STRIKES = 20;            // ATM ke dono taraf — app.py BINANCE_OC_STRIKE_WINDOW jaisa
const WARMUP_MS = 6000;                // cold start par pehla data aane tak max wait
const QUOTE_PRUNE_MS = 30 * 60 * 1000; // itni der se update nahi hua (expired contract) to hata do

const UPSTREAM_MAP: Record<string, string> = {
  "/api/": "https://api.binance.com/api/",
  "/eapi/": "https://eapi.binance.com/eapi/",
};

const BN_WS = {
  mark: "wss://fstream.binance.com/market/stream?streams=btcusdt@optionMarkPrice",
  trade: "wss://fstream.binance.com/public/stream?streams=btcusdt@optionTrade",
  spot: "wss://stream.binance.com:9443/ws/btcusdt@aggTrade",
};

// Legacy raw relay map (rollback ke liye) — naya app.py inhe use nahi karta.
const WS_MAP: Record<string, string> = {
  "/ws/mark": BN_WS.mark,
  "/ws/trade": BN_WS.trade,
  "/ws/spot": BN_WS.spot,
};

// ── Helpers ───────────────────────────────────────────────────────────────
const nowMs = () => Date.now();
const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

function num(x: unknown): number {
  const n = typeof x === "number" ? x : parseFloat(String(x ?? ""));
  return Number.isFinite(n) ? n : 0;
}
function present(v: unknown): boolean {
  return v !== undefined && v !== null && v !== "";
}

async function respondText(
  req: Request,
  text: string,
  status = 200,
  contentType = "application/json",
): Promise<Response> {
  const headers = new Headers({
    "Content-Type": contentType,
    "Access-Control-Allow-Origin": "*",
    "Cache-Control": "no-store",
  });
  const acceptsGzip = /\bgzip\b/i.test(req.headers.get("accept-encoding") ?? "");
  if (acceptsGzip && text.length > 1024) {
    const gz = new Blob([text]).stream().pipeThrough(new CompressionStream("gzip"));
    const buf = await new Response(gz).arrayBuffer();
    headers.set("Content-Encoding", "gzip");
    headers.set("Vary", "Accept-Encoding");
    return new Response(buf, { status, headers });
  }
  return new Response(text, { status, headers });
}
const respondJson = (req: Request, body: unknown, status = 200) =>
  respondText(req, JSON.stringify(body), status);

// ── Live store (memory mein) ──────────────────────────────────────────────
// Field names bilkul wahi jo app.py ke _BN_LIVE_QUOTES rows mein use hote hain.
interface Quote {
  mark?: string; bid?: string; ask?: string; last?: string;
  delta?: string; gamma?: string; theta?: string; vega?: string;
  iv?: string; buy_iv?: string; sell_iv?: string;
  oi?: number; chg?: number; chgp?: number; volume?: number; vol_cum?: number;
  mark_ts?: number; last_ts?: number; ts: number;
}
type StrField =
  | "mark" | "bid" | "ask" | "last" | "delta" | "gamma"
  | "theta" | "vega" | "iv" | "buy_iv" | "sell_iv";

const quotes = new Map<string, Quote>();
const symInfo = new Map<string, { exp: string; strike: number }>();
const spot: { price: number | null; ts: number } = { price: null, ts: 0 };

function getQuote(sym: unknown): Quote | null {
  if (typeof sym !== "string" || !sym.startsWith(SYMBOL_PREFIX)) return null;
  let q = quotes.get(sym);
  if (!q) {
    const p = sym.split("-"); // BTC-YYMMDD-STRIKE-C|P
    const strike = Number(p[2]);
    if (p.length < 4 || !Number.isFinite(strike)) return null;
    q = { ts: nowMs() };
    quotes.set(sym, q);
    symInfo.set(sym, { exp: p[1], strike });
  }
  return q;
}

// Mark-stream keys → app.py ke row keys (app.py _BN_MARK_FIELD_MAP jaisa hi)
const MARK_FIELD_MAP: Array<[StrField, string]> = [
  ["last", "c"], ["bid", "bo"], ["ask", "ao"], ["mark", "mp"],
  ["delta", "d"], ["gamma", "g"], ["theta", "t"], ["vega", "v"],
  ["iv", "vo"], ["buy_iv", "b"], ["sell_iv", "a"],
];

// deno-lint-ignore no-explicit-any
function unwrap(data: any): any[] {
  const payload = data && typeof data === "object" && "data" in data ? data.data : data;
  if (Array.isArray(payload)) return payload;
  return payload && typeof payload === "object" ? [payload] : [];
}

// deno-lint-ignore no-explicit-any
function applyMark(msg: any) {
  const q = getQuote(msg?.s);
  if (!q) return;
  const t = nowMs();
  for (const [target, key] of MARK_FIELD_MAP) {
    if (present(msg[key])) {
      q[target] = msg[key];
      if (target === "mark") q.mark_ts = t;
    }
  }
  q.ts = t;
}

// deno-lint-ignore no-explicit-any
function applyTrade(msg: any) {
  const q = getQuote(msg?.s);
  if (!q) return;
  const t = nowMs();
  if (present(msg.p)) {
    q.last = msg.p;
    q.last_ts = t;
  }
  if (present(msg.q)) q.vol_cum = (q.vol_cum ?? 0) + num(msg.q);
  q.ts = t;
}

// deno-lint-ignore no-explicit-any
function applySpot(msg: any) {
  const m = msg && typeof msg === "object" && msg.data ? msg.data : msg;
  const p = parseFloat(m?.p);
  if (Number.isFinite(p)) {
    spot.price = p;
    spot.ts = nowMs();
  }
}

// ── Upstream Binance WebSocket feed (auto-reconnect + backoff + watchdog) ──
class Feed {
  ws: WebSocket | null = null;
  connected = false;
  stopped = true;
  lastMsg = 0;
  msgCount = 0;
  failCount = 0;
  // deno-lint-ignore no-explicit-any
  retryTimer: any = null;
  // deno-lint-ignore no-explicit-any
  constructor(public name: string, public url: string, public onData: (d: any) => void, public staleMs: number) {}

  start() {
    if (!this.stopped) return;
    this.stopped = false;
    this.failCount = 0;
    this.connect();
  }
  stop() {
    this.stopped = true;
    clearTimeout(this.retryTimer);
    const ws = this.ws;
    this.ws = null;
    this.connected = false;
    try { ws?.close(); } catch { /* ignore */ }
  }
  private connect() {
    if (this.stopped) return;
    let ws: WebSocket;
    try {
      ws = new WebSocket(this.url);
    } catch {
      this.scheduleRetry();
      return;
    }
    this.ws = ws;
    ws.onopen = () => {
      if (this.ws !== ws) return;
      this.connected = true;
      this.failCount = 0;
      this.lastMsg = nowMs();
    };
    ws.onmessage = (e: MessageEvent) => {
      if (this.ws !== ws) return;
      this.lastMsg = nowMs();
      this.msgCount++;
      try {
        this.onData(JSON.parse(typeof e.data === "string" ? e.data : ""));
      } catch { /* bad frame ignore */ }
    };
    ws.onerror = () => { try { ws.close(); } catch { /* ignore */ } };
    ws.onclose = () => {
      if (this.ws !== ws) return; // stop() ya watchdog ne already handle kiya
      this.ws = null;
      this.connected = false;
      this.scheduleRetry();
    };
  }
  private scheduleRetry() {
    if (this.stopped) return;
    this.failCount++;
    const delay = Math.min(1000 * 2 ** (this.failCount - 1), 30000) + Math.random() * 500;
    this.retryTimer = setTimeout(() => this.connect(), delay);
  }
  // Half-open TCP: socket "open" hai par data nahi aa raha → force reconnect
  watchdog() {
    if (this.stopped || !this.ws || this.staleMs <= 0) return;
    if (nowMs() - this.lastMsg > this.staleMs) {
      try { this.ws.close(); } catch { /* ignore */ }
    }
  }
  status(t: number) {
    return { connected: this.connected, age_ms: this.lastMsg ? t - this.lastMsg : null };
  }
}

const feeds = {
  mark: new Feed("mark", BN_WS.mark, (d) => { for (const it of unwrap(d)) applyMark(it); }, 30_000),
  trade: new Feed("trade", BN_WS.trade, (d) => { for (const it of unwrap(d)) applyTrade(it); }, 0), // trade sparse hoti hai
  spot: new Feed("spot", BN_WS.spot, applySpot, 30_000),
};
let feedsStopped = true;
let lastDemand = 0;

function ensureFeeds(): boolean {
  lastDemand = nowMs();
  const wasCold = feedsStopped;
  if (feedsStopped) {
    feedsStopped = false;
    for (const f of Object.values(feeds)) f.start();
  }
  return wasCold;
}

function stopFeedsAndClear() {
  for (const f of Object.values(feeds)) f.stop();
  feedsStopped = true;
  quotes.clear();
  symInfo.clear();
  spot.price = null;
  spot.ts = 0;
  tickerTs = 0;
}

setInterval(() => {
  const t = nowMs();
  if (!feedsStopped && t - lastDemand > IDLE_STOP_MS) {
    stopFeedsAndClear();
    return;
  }
  if (!feedsStopped) {
    for (const f of Object.values(feeds)) f.watchdog();
    for (const [sym, q] of quotes) {
      if (t - q.ts > QUOTE_PRUNE_MS) {
        quotes.delete(sym);
        symInfo.delete(sym);
      }
    }
  }
}, 5000);

// ── 24h ticker (OI / volume / change%) — WS par nahi aata, isliye REST ─────
let tickerTs = 0;
let tickerErr: string | null = null;
let tickerInflight: Promise<void> | null = null;

function refreshTicker(force = false): Promise<void> {
  if (tickerInflight) return tickerInflight;
  if (!force && nowMs() - tickerTs < TICKER_TTL_MS) return Promise.resolve();
  tickerInflight = (async () => {
    try {
      const r = await fetch("https://eapi.binance.com/eapi/v1/ticker");
      if (!r.ok) {
        tickerErr = `ticker HTTP ${r.status}`;
        return;
      }
      const arr = await r.json();
      if (!Array.isArray(arr)) {
        tickerErr = "ticker: unexpected response";
        return;
      }
      const t = nowMs();
      for (const x of arr) {
        const q = getQuote(x?.symbol);
        if (!q) continue;
        q.oi = num(x.openInterest);
        q.chg = num(x.priceChange);
        q.chgp = num(x.priceChangePercent);
        q.volume = num(x.volume);
        // WS ne abhi tak kuch na bheja ho to REST se ek baar fallback fill (app.py jaisa)
        if (!present(q.bid) && present(x.bidPrice)) q.bid = String(x.bidPrice);
        if (!present(q.ask) && present(x.askPrice)) q.ask = String(x.askPrice);
        if (!present(q.last) && present(x.lastPrice)) q.last = String(x.lastPrice);
        if (!present(q.mark) && present(x.lastPrice)) q.mark = String(x.lastPrice);
        q.ts = t;
      }
      tickerTs = t;
      tickerErr = null;
    } catch (e) {
      tickerErr = `ticker fetch failed: ${e}`;
    } finally {
      tickerInflight = null;
    }
  })();
  return tickerInflight;
}

async function fetchSpotRest() {
  try {
    const r = await fetch("https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT");
    if (!r.ok) return;
    const j = await r.json();
    const p = parseFloat(j?.price);
    if (Number.isFinite(p)) {
      spot.price = p;
      spot.ts = nowMs();
    }
  } catch { /* ignore */ }
}

// Cold start: pehla mark tick + spot + ticker aane tak (max maxMs) ruko
async function warmup(maxMs: number) {
  const t0 = nowMs();
  const wait = (async () => {
    while (nowMs() - t0 < maxMs) {
      if (feeds.mark.msgCount > 0 && spot.price !== null) return;
      await sleep(100);
    }
  })();
  await Promise.all([refreshTicker(true), wait]);
  if (spot.price === null) await fetchSpotRest();
}

// ── Snapshot builder ──────────────────────────────────────────────────────
// Row = [symbol, ...SNAP_KEYS ki values]. Keys response mein ek baar jaati hain.
const SNAP_KEYS = [
  "mark", "bid", "ask", "last", "delta", "gamma", "theta", "vega",
  "iv", "buy_iv", "sell_iv", "oi", "chg", "chgp", "volume", "vol_cum",
  "mark_age_ms", "last_age_ms",
] as const;

function rowFor(sym: string, q: Quote, t: number): unknown[] {
  const row: unknown[] = [sym];
  for (const k of SNAP_KEYS) {
    if (k === "mark_age_ms") row.push(q.mark_ts ? t - q.mark_ts : null);
    else if (k === "last_age_ms") row.push(q.last_ts ? t - q.last_ts : null);
    else row.push(q[k] ?? null);
  }
  return row;
}

function buildSnapshot(strikeWindow: number, maxExpiries: number) {
  const t = nowMs();
  const byExp = new Map<string, Map<number, string[]>>();
  for (const [sym, info] of symInfo) {
    if (!quotes.has(sym)) continue;
    let sm = byExp.get(info.exp);
    if (!sm) byExp.set(info.exp, (sm = new Map()));
    const arr = sm.get(info.strike);
    if (arr) arr.push(sym);
    else sm.set(info.strike, [sym]);
  }

  const rows: unknown[][] = [];
  const price = spot.price;
  if (price !== null) {
    let exps = [...byExp.keys()].sort(); // YYMMDD → lexicographic = chronological
    if (maxExpiries > 0) exps = exps.slice(0, maxExpiries);
    for (const exp of exps) {
      const sm = byExp.get(exp)!;
      const strikes = [...sm.keys()].sort((a, b) => a - b);
      let atmIdx = 0;
      let best = Infinity;
      strikes.forEach((s, i) => {
        const d = Math.abs(s - price);
        if (d < best) { best = d; atmIdx = i; }
      });
      const lo = Math.max(0, atmIdx - strikeWindow);
      const hi = Math.min(strikes.length, atmIdx + strikeWindow + 1);
      for (let i = lo; i < hi; i++) {
        for (const sym of sm.get(strikes[i])!) {
          const q = quotes.get(sym);
          if (q) rows.push(rowFor(sym, q, t));
        }
      }
    }
  }

  return {
    v: 1,
    now_ms: t,
    spot: price,
    spot_age_ms: spot.ts ? t - spot.ts : null,
    feeds: { mark: feeds.mark.status(t), trade: feeds.trade.status(t), spot: feeds.spot.status(t) },
    ticker_age_ms: tickerTs ? t - tickerTs : null,
    ticker_error: tickerErr,
    total_symbols: quotes.size,
    keys: SNAP_KEYS,
    rows,
  };
}

async function handleSnapshot(req: Request, url: URL): Promise<Response> {
  const sw = parseInt(url.searchParams.get("strikes") ?? "", 10);
  const strikeWindow = Number.isFinite(sw) && sw >= 0 ? Math.min(sw, 1000) : DEFAULT_STRIKES;
  const me = parseInt(url.searchParams.get("expiries") ?? "", 10);
  const maxExpiries = Number.isFinite(me) && me > 0 ? me : 0; // 0 = saari expiries

  const cold = ensureFeeds();
  if (cold) await warmup(WARMUP_MS);
  else void refreshTicker(false);

  return respondJson(req, buildSnapshot(strikeWindow, maxExpiries));
}

// ── Filtered + cached REST (sirf bina-symbol wale bade calls) ──────────────
const REST_TTL_MS: Record<string, number> = {
  "/eapi/v1/exchangeInfo": 300_000, // strikes/expiries rarely badalte hain
  "/eapi/v1/ticker": 3_000,
  "/eapi/v1/mark": 2_000,
};
const restCache = new Map<string, { ts: number; status: number; text: string; ct: string }>();

// deno-lint-ignore no-explicit-any
function filterRest(pathname: string, data: any): any {
  if (pathname === "/eapi/v1/exchangeInfo" && data && Array.isArray(data.optionSymbols)) {
    return {
      serverTime: data.serverTime,
      optionSymbols: data.optionSymbols
        // deno-lint-ignore no-explicit-any
        .filter((s: any) => s?.underlying === "BTCUSDT" || String(s?.symbol ?? "").startsWith(SYMBOL_PREFIX))
        // deno-lint-ignore no-explicit-any
        .map((s: any) => ({
          symbol: s.symbol,
          underlying: s.underlying,
          strikePrice: s.strikePrice,
          expiryDate: s.expiryDate,
          side: s.side,
        })),
    };
  }
  if ((pathname === "/eapi/v1/ticker" || pathname === "/eapi/v1/mark") && Array.isArray(data)) {
    // deno-lint-ignore no-explicit-any
    return data.filter((x: any) => String(x?.symbol ?? "").startsWith(SYMBOL_PREFIX));
  }
  return data;
}

// ── Server ────────────────────────────────────────────────────────────────
Deno.serve({ port: PORT }, async (req: Request) => {
  const url = new URL(req.url);

  // ── Legacy WebSocket relay (rollback ke liye; poora data forward karta hai) ──
  if (req.headers.get("upgrade")?.toLowerCase() === "websocket") {
    const targetWsUrl = WS_MAP[url.pathname];
    if (!targetWsUrl) {
      return new Response("Unknown WS path — /ws/mark, /ws/trade, /ws/spot use karo", { status: 404 });
    }
    const { socket: clientSocket, response } = Deno.upgradeWebSocket(req);
    const upstreamSocket = new WebSocket(targetWsUrl);
    upstreamSocket.onmessage = (e) => {
      if (clientSocket.readyState === WebSocket.OPEN) clientSocket.send(e.data);
    };
    upstreamSocket.onclose = () => {
      if (clientSocket.readyState === WebSocket.OPEN) clientSocket.close();
    };
    upstreamSocket.onerror = () => {
      if (clientSocket.readyState === WebSocket.OPEN) clientSocket.close();
    };
    clientSocket.onmessage = (e) => {
      if (upstreamSocket.readyState === WebSocket.OPEN) upstreamSocket.send(e.data);
    };
    clientSocket.onclose = () => {
      if (upstreamSocket.readyState === WebSocket.OPEN) upstreamSocket.close();
    };
    return response;
  }

  try {
    // Health-check
    if (url.pathname === "/") {
      return new Response("Binance proxy is running ✅ (on-demand /snapshot enabled)", { status: 200 });
    }

    // Diagnostics — koi secret nahi
    if (url.pathname === "/status") {
      const t = nowMs();
      return respondJson(req, {
        feeds_running: !feedsStopped,
        last_snapshot_request_age_ms: lastDemand ? t - lastDemand : null,
        symbols: quotes.size,
        spot: spot.price,
        ticker_age_ms: tickerTs ? t - tickerTs : null,
        ticker_error: tickerErr,
        feeds: { mark: feeds.mark.status(t), trade: feeds.trade.status(t), spot: feeds.spot.status(t) },
        rest_filter: FILTER_REST,
      });
    }

    // ── On-demand snapshot ──
    if (url.pathname === "/snapshot") {
      return await handleSnapshot(req, url);
    }

    // ── REST forward ──
    const prefix = Object.keys(UPSTREAM_MAP).find((p) => url.pathname.startsWith(p));
    if (!prefix) {
      return new Response("Not found — /snapshot, /api/... ya /eapi/... use karo", { status: 404 });
    }

    const upstreamUrl = UPSTREAM_MAP[prefix] + url.pathname.slice(prefix.length) + url.search;

    const ttl = REST_TTL_MS[url.pathname];
    const filterable = FILTER_REST && req.method === "GET" && ttl !== undefined && !url.searchParams.has("symbol");
    if (filterable) {
      const hit = restCache.get(url.pathname);
      if (hit && nowMs() - hit.ts < ttl) return respondText(req, hit.text, hit.status, hit.ct);
    }

    const upstreamResp = await fetch(upstreamUrl, {
      method: req.method,
      headers: {
        "X-MBX-APIKEY": req.headers.get("X-MBX-APIKEY") ?? "",
        "Content-Type": req.headers.get("Content-Type") ?? "application/json",
      },
      body: req.method === "GET" || req.method === "HEAD" ? undefined : await req.text(),
    });

    let respBody = await upstreamResp.text();
    const ct = upstreamResp.headers.get("Content-Type") ?? "application/json";

    if (filterable && upstreamResp.status === 200) {
      try {
        respBody = JSON.stringify(filterRest(url.pathname, JSON.parse(respBody)));
        restCache.set(url.pathname, { ts: nowMs(), status: 200, text: respBody, ct });
      } catch { /* parse fail → original body as-is */ }
    }

    return respondText(req, respBody, upstreamResp.status, ct);
  } catch (err) {
    return new Response(JSON.stringify({ error: `Proxy failed: ${err}` }), {
      status: 502,
      headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" },
    });
  }
});
