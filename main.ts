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
// NEW: /trade/... endpoints (Binance Options, token-protected) — neeche "TRADE MODULE" dekho.
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

// ── TRADE MODULE (Binance Options, eapi) ──────────────────────────────────
// Phase 1: read-only endpoints + guarded order/cancel.
// Secrets sirf Render env mein: OPT_API_KEY, OPT_SECRET_KEY, TRADE_TOKEN.
// Ye endpoints (/trade/...) sirf header `X-Trade-Token` sahi hone par chalte hain.
// Koi CORS header nahi — browser se nahi, sirf server-to-server (HF Python) use ke liye.
//
// Env vars:
//   OPT_API_KEY / OPT_SECRET_KEY   Binance Options key (Withdrawal OFF rakho)
//   TRADE_TOKEN                    lamba random secret (HF ke paas bhi wahi hoga)
//   TRADING_ENABLED                "true" ho tabhi naye orders jayenge (default false)
//   MAX_ORDER_QTY                  default 0.01   — ek order ki max quantity
//   MAX_ORDER_USDT                 default 3      — BUY order ka max premium (price*qty)
//   MAX_PRICE_DEV_PCT              default 50     — order price mark se itne % se zyada door nahi
//   MAX_ORDERS_PER_MIN             default 6      — rate limit
//
// Rules: shuru mein sirf BUY. SELL sirf reduceOnly=true (position band karne ke liye).
// Cancel / cancel-all hamesha allowed hain (kill-switch se bhi nahi rukte).

const EAPI = "https://eapi.binance.com";
const OPT_API_KEY = (Deno.env.get("OPT_API_KEY") ?? "").trim();
const OPT_SECRET_KEY = (Deno.env.get("OPT_SECRET_KEY") ?? "").trim();
const TRADE_TOKEN = (Deno.env.get("TRADE_TOKEN") ?? "").trim();
const TRADING_ENABLED = (Deno.env.get("TRADING_ENABLED") ?? "false").trim().toLowerCase() === "true";
const MAX_ORDER_QTY = envNum("MAX_ORDER_QTY", 0.01);
const MAX_ORDER_USDT = envNum("MAX_ORDER_USDT", 3);
const MAX_PRICE_DEV_PCT = envNum("MAX_PRICE_DEV_PCT", 50);
const MAX_ORDERS_PER_MIN = envNum("MAX_ORDERS_PER_MIN", 6);

function envNum(name: string, def: number): number {
  const v = parseFloat(Deno.env.get(name) ?? "");
  return Number.isFinite(v) && v > 0 ? v : def;
}

const OPTION_SYMBOL_RE = /^BTC-\d{6}-\d{3,7}-[CP]$/;
const CLIENT_ID_RE = /^[A-Za-z0-9_-]{1,36}$/;
const textEnc = new TextEncoder();

function tradeJson(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

async function sha256Bytes(s: string): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", textEnc.encode(s)));
}

async function hmacHex(secret: string, msg: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw", textEnc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"],
  );
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", key, textEnc.encode(msg)));
  return [...sig].map((b) => b.toString(16).padStart(2, "0")).join("");
}

// Token compare (constant-time, hash ke through)
async function tokenOk(provided: string): Promise<boolean> {
  if (!TRADE_TOKEN || !provided) return false;
  const [a, b] = await Promise.all([sha256Bytes(provided), sha256Bytes(TRADE_TOKEN)]);
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

// Galat token bar-bar aaye to us IP ko thodi der ke liye rok do
const authFails = new Map<string, { n: number; first: number; lockUntil: number }>();
function clientIp(req: Request): string {
  return (req.headers.get("x-forwarded-for") ?? "unknown").split(",")[0].trim();
}
function isLocked(ip: string): boolean {
  const f = authFails.get(ip);
  return !!f && f.lockUntil > nowMs();
}
function noteAuthFail(ip: string) {
  const t = nowMs();
  let f = authFails.get(ip);
  if (!f || t - f.first > 10 * 60_000) f = { n: 0, first: t, lockUntil: 0 };
  f.n++;
  if (f.n >= 10) f.lockUntil = t + 10 * 60_000;
  authFails.set(ip, f);
}

// Binance server time offset (timestamp galat na jaye)
let timeOffsetMs = 0;
let timeSyncedAt = 0;
async function syncTime() {
  if (nowMs() - timeSyncedAt < 10 * 60_000) return;
  try {
    const r = await fetch(`${EAPI}/eapi/v1/time`, { signal: AbortSignal.timeout(5000) });
    if (r.ok) {
      const j = await r.json();
      if (Number.isFinite(Number(j?.serverTime))) {
        timeOffsetMs = Number(j.serverTime) - nowMs();
        timeSyncedAt = nowMs();
      }
    }
  } catch { /* ignore, purana offset chalega */ }
}

type Params = Record<string, string | number | boolean | undefined>;

function buildQuery(p: Params): string {
  return Object.entries(p)
    .filter(([, v]) => v !== undefined && v !== "")
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
    .join("&");
}

async function signedCall(method: "GET" | "POST" | "DELETE", path: string, params: Params) {
  await syncTime();
  const qs = buildQuery({ ...params, recvWindow: 5000, timestamp: nowMs() + timeOffsetMs });
  const sig = await hmacHex(OPT_SECRET_KEY, qs);
  const r = await fetch(`${EAPI}${path}?${qs}&signature=${sig}`, {
    method,
    headers: { "X-MBX-APIKEY": OPT_API_KEY },
    signal: AbortSignal.timeout(15000),
  });
  const text = await r.text();
  let data: unknown;
  try { data = JSON.parse(text); } catch { data = { raw: text.slice(0, 500) }; }
  return { status: r.status, data };
}

// URL se sirf allowed query params uthao
function pick(url: URL, names: string[]): Params {
  const out: Params = {};
  for (const n of names) {
    const v = url.searchParams.get(n);
    if (v !== null && v !== "") out[n] = v;
  }
  return out;
}

async function readBody(req: Request): Promise<Record<string, unknown> | null> {
  const text = await req.text();
  if (text.length > 2000) return null;
  try {
    const j = JSON.parse(text);
    return j && typeof j === "object" && !Array.isArray(j) ? j : null;
  } catch {
    return null;
  }
}

// Order safety state
const recentClientIds = new Map<string, number>();
const orderTimes: number[] = [];

function newClientId(): string {
  return `rn${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

async function getMarkPrice(symbol: string): Promise<number | null> {
  try {
    const r = await fetch(`${EAPI}/eapi/v1/mark?symbol=${encodeURIComponent(symbol)}`, {
      signal: AbortSignal.timeout(8000),
    });
    if (!r.ok) return null;
    const j = await r.json();
    const row = Array.isArray(j) ? j[0] : j;
    const p = parseFloat(row?.markPrice);
    return Number.isFinite(p) && p > 0 ? p : null;
  } catch {
    return null;
  }
}

async function handlePlaceOrder(req: Request): Promise<Response> {
  if (!TRADING_ENABLED) return tradeJson(403, { error: "Trading disabled (TRADING_ENABLED != true)" });

  const b = await readBody(req);
  if (!b) return tradeJson(400, { error: "Bad JSON body" });

  const symbol = String(b.symbol ?? "");
  const side = String(b.side ?? "").toUpperCase();
  const qty = Number(b.quantity);
  const price = Number(b.price);
  const tif = String(b.timeInForce ?? "GTC").toUpperCase();
  const reduceOnly = b.reduceOnly === true;
  const postOnly = b.postOnly === true;
  const clientOrderId = b.clientOrderId ? String(b.clientOrderId) : newClientId();

  if (!OPTION_SYMBOL_RE.test(symbol)) return tradeJson(400, { error: "Symbol invalid (sirf BTC-YYMMDD-STRIKE-C|P)" });
  if (side !== "BUY" && side !== "SELL") return tradeJson(400, { error: "side BUY ya SELL" });
  if (side === "SELL" && !reduceOnly) return tradeJson(403, { error: "SELL sirf reduceOnly=true ke saath allowed hai" });
  if (!Number.isFinite(qty) || qty <= 0) return tradeJson(400, { error: "quantity invalid" });
  if (qty > MAX_ORDER_QTY) return tradeJson(403, { error: `quantity limit se zyada (max ${MAX_ORDER_QTY})` });
  if (!Number.isFinite(price) || price <= 0) return tradeJson(400, { error: "price invalid (LIMIT order ke liye zaroori)" });
  if (!["GTC", "IOC", "FOK"].includes(tif)) return tradeJson(400, { error: "timeInForce GTC/IOC/FOK" });
  if (!CLIENT_ID_RE.test(clientOrderId)) return tradeJson(400, { error: "clientOrderId invalid" });
  if (side === "BUY" && price * qty > MAX_ORDER_USDT) {
    return tradeJson(403, { error: `premium limit se zyada (price*qty > ${MAX_ORDER_USDT} USDT)` });
  }

  // Duplicate + rate limit (sab await se pehle, taaki race na ho)
  const t = nowMs();
  for (const [id, ts] of recentClientIds) if (t - ts > 10 * 60_000) recentClientIds.delete(id);
  if (recentClientIds.has(clientOrderId)) return tradeJson(409, { error: "Duplicate clientOrderId" });
  while (orderTimes.length && t - orderTimes[0] > 60_000) orderTimes.shift();
  if (orderTimes.length >= MAX_ORDERS_PER_MIN) return tradeJson(429, { error: "Order rate limit (per minute) cross" });
  recentClientIds.set(clientOrderId, t);
  orderTimes.push(t);

  // Fat-finger check: price mark se bahut door na ho
  const mark = await getMarkPrice(symbol);
  if (mark === null) {
    recentClientIds.delete(clientOrderId);
    return tradeJson(502, { error: "Mark price nahi mila — order roka gaya" });
  }
  const dev = MAX_PRICE_DEV_PCT / 100;
  if ((side === "BUY" && price > mark * (1 + dev)) || (side === "SELL" && price < mark * (1 - dev))) {
    recentClientIds.delete(clientOrderId);
    return tradeJson(403, { error: `price mark (${mark}) se ${MAX_PRICE_DEV_PCT}% se zyada door hai` });
  }

  const res = await signedCall("POST", "/eapi/v1/order", {
    symbol, side, type: "LIMIT", quantity: qty, price, timeInForce: tif,
    reduceOnly: reduceOnly ? "true" : undefined,
    postOnly: postOnly ? "true" : undefined,
    clientOrderId, newOrderRespType: "RESULT",
  });
  console.log(`[trade] order ${side} ${symbol} q=${qty} p=${price} cid=${clientOrderId} -> HTTP ${res.status}`);
  return tradeJson(res.status, res.data);
}

async function handleCancel(req: Request): Promise<Response> {
  const b = await readBody(req);
  if (!b) return tradeJson(400, { error: "Bad JSON body" });
  const symbol = String(b.symbol ?? "");
  if (!OPTION_SYMBOL_RE.test(symbol)) return tradeJson(400, { error: "Symbol invalid" });
  if (!b.orderId && !b.clientOrderId) return tradeJson(400, { error: "orderId ya clientOrderId do" });
  const res = await signedCall("DELETE", "/eapi/v1/order", {
    symbol,
    orderId: b.orderId ? String(b.orderId) : undefined,
    clientOrderId: b.clientOrderId ? String(b.clientOrderId) : undefined,
  });
  console.log(`[trade] cancel ${symbol} -> HTTP ${res.status}`);
  return tradeJson(res.status, res.data);
}

async function handleTrade(req: Request, url: URL): Promise<Response> {
  const ip = clientIp(req);
  if (isLocked(ip)) return tradeJson(429, { error: "Bahut galat attempts — thodi der baad try karo" });
  if (!TRADE_TOKEN) return tradeJson(503, { error: "TRADE_TOKEN set nahi hai" });
  if (!(await tokenOk(req.headers.get("x-trade-token") ?? ""))) {
    noteAuthFail(ip);
    return tradeJson(401, { error: "Unauthorized" });
  }
  if (!OPT_API_KEY || !OPT_SECRET_KEY) return tradeJson(503, { error: "OPT_API_KEY / OPT_SECRET_KEY set nahi hain" });

  const path = url.pathname;
  const m = req.method;
  try {
    if (path === "/trade/status" && m === "GET") {
      return tradeJson(200, {
        trading_enabled: TRADING_ENABLED,
        keys_configured: true,
        limits: {
          max_order_qty: MAX_ORDER_QTY,
          max_order_usdt: MAX_ORDER_USDT,
          max_price_dev_pct: MAX_PRICE_DEV_PCT,
          max_orders_per_min: MAX_ORDERS_PER_MIN,
        },
        rules: "BUY + reduceOnly SELL only, LIMIT only",
      });
    }
    if (path === "/trade/account" && m === "GET") {
      const r = await signedCall("GET", "/eapi/v1/marginAccount", {});
      return tradeJson(r.status, r.data);
    }
    if (path === "/trade/positions" && m === "GET") {
      const r = await signedCall("GET", "/eapi/v1/position", pick(url, ["symbol"]));
      return tradeJson(r.status, r.data);
    }
    if (path === "/trade/orders/open" && m === "GET") {
      const r = await signedCall("GET", "/eapi/v1/openOrders", pick(url, ["symbol", "orderId", "limit"]));
      return tradeJson(r.status, r.data);
    }
    if (path === "/trade/orders/history" && m === "GET") {
      const r = await signedCall("GET", "/eapi/v1/historyOrders", pick(url, ["symbol", "orderId", "startTime", "endTime", "limit"]));
      return tradeJson(r.status, r.data);
    }
    if (path === "/trade/fills" && m === "GET") {
      const r = await signedCall("GET", "/eapi/v1/userTrades", pick(url, ["symbol", "fromId", "startTime", "endTime", "limit"]));
      return tradeJson(r.status, r.data);
    }
    if (path === "/trade/order" && m === "POST") return await handlePlaceOrder(req);
    if (path === "/trade/cancel" && m === "POST") return await handleCancel(req);
    if (path === "/trade/cancel-all" && m === "POST") {
      const r = await signedCall("DELETE", "/eapi/v1/allOpenOrdersByUnderlying", { underlying: "BTCUSDT" });
      console.log(`[trade] cancel-all -> HTTP ${r.status}`);
      return tradeJson(r.status, r.data);
    }
    return tradeJson(404, { error: "Unknown /trade path or method" });
  } catch (e) {
    // Error message mein secret nahi hota (key sirf header mein jaati hai)
    return tradeJson(502, { error: `Trade call failed: ${String(e).slice(0, 200)}` });
  }
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

    // ── Trade (Options) — token-protected ──
    if (url.pathname.startsWith("/trade/")) {
      return await handleTrade(req, url);
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
