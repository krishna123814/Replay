// main.ts — My-engine Render server (Binance OPTIONS trade + rules engine + public relay)
//
// Runtime: Node 22+ (ya Bun / Deno 2). Sirf built-in modules + global fetch —
// koi npm dependency nahi. TypeScript-only runtime features (enum etc.) use nahi kiye,
// isliye `node --experimental-strip-types main.ts`, `tsx main.ts`, `bun main.ts` sab chalte hain.
//
// (2026-10-06) SL/Target/Trailing hardening: bid=0 par mark fallback, trailing high fill se, level validation (order se pehle),
//   2-tick confirmation, exit kabhi give-up nahi (escalating price), unprotected-position alert, engine-gap alert, self keep-alive,
//   MAX_ORDER_* sirf BUY par, SELL ke saath rule nahi. Naye env (optional): RENDER_EXTERNAL_URL/SELF_URL, UNPROTECTED_ALERT.
//
// ENV: PORT, OPT_API_KEY, OPT_SECRET_KEY, TRADE_TOKEN, TRADING_ENABLED (true/false),
//      BREVO_API_KEY, ALERT_EMAIL_TO, [BREVO_SENDER_EMAIL], [TELEGRAM_BOT_TOKEN + TELEGRAM_CHAT_ID  (ya TELEGRAM_RELAY_URL + RELAY_SECRET)], [MAX_ORDER_QTY], [MAX_ORDER_USDT],
//      HF_STORE_URL + HF_STORE_TOKEN (rules / trade journal / render_log HF Space ke /data me — app.py ke /api/render_store/*;
//        HF_STORE_URL = https://<owner>-<space>.hf.space ; HF_STORE_TOKEN = app.py wale RENDER_STORE_TOKEN jaisa hi), [HF_ACCESS_TOKEN: sirf Space private ho to]
//      SECURE_PASSPHRASE (browser se encrypted POST /secure ke liye; min 12 chars)
//      render_log / trade_journal / rules: HF Space persistent storage (/data/app_state/render/) par
//
// LOG: is service ka render_log HF par "log" store me jaata hai (render_log.jsonl). Har row me service:"replay".
//      Dusri service (telegram-aib3, telegram_relay.js) ka log ALAG hai ("log_telegram"). Guide: RENDER_LOG_GUIDE.md
// Contract:
//   Auth: header X-Trade-Token == TRADE_TOKEN  (sirf /trade/* aur /rules/* par)
//   GET  /trade/status /trade/account /trade/positions /trade/orders/open[?symbol]
//        /trade/ticksize?symbol /trade/fills /trade/orders/history /trade/bill /trade/exercise
//   POST /trade/order /trade/cancel /trade/cancel-all /trade/panic
//   POST /rules/create  GET /rules/list  POST /rules/cancel
//   POST /secure  (browser se AES-256-GCM encrypted; actions: ping, trade_data, trade_command[place_order,cancel_order,cancel_all,panic,close_position,add_rule,remove_rule])
//   GET  /health /ping /  (no auth, keep-alive)
//   GET  /api/v3/* , /eapi/v1/*  -> SIRF public whitelist (signed/private calls yahan se nahi jaate)

import { createServer } from "node:http";
import type { IncomingMessage, ServerResponse } from "node:http";
import { createCipheriv, createDecipheriv, createHash, createHmac, pbkdf2Sync, randomBytes, timingSafeEqual } from "node:crypto";
import { appendFileSync, readFileSync, writeFileSync } from "node:fs";
import { gzipSync } from "node:zlib";
import { AsyncLocalStorage } from "node:async_hooks";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Json = any;
type Params = Record<string, string | number | boolean | null | undefined>;
type BResult = { ok: boolean; status: number; data: Json };

// ───────────────────────── config ─────────────────────────
const env = (k: string, d = ""): string => (process.env[k] ?? d).trim();
const PORT = Number(env("PORT", "10000")) || 10000;
const API_KEY = env("OPT_API_KEY");
const SECRET = env("OPT_SECRET_KEY");
const TRADE_TOKEN = env("TRADE_TOKEN");
const MAX_ORDER_QTY = Number(env("MAX_ORDER_QTY", "10")) || 10;
const MAX_ORDER_USDT = Number(env("MAX_ORDER_USDT", "5000")) || 5000;
const BREVO_KEY = env("BREVO_API_KEY");
const ALERT_TO = env("ALERT_EMAIL_TO");
const ALERT_FROM = env("BREVO_SENDER_EMAIL") || ALERT_TO;
const STORE_URL = env("HF_STORE_URL").replace(/\/+$/, "");
const STORE_TOKEN = env("HF_STORE_TOKEN");
const HF_ACCESS = env("HF_ACCESS_TOKEN");
const STORE_ON = !!(STORE_URL && STORE_TOKEN);
// Telegram alerts (optional) — direct bot (TOKEN + CHAT_ID) ya app.py wala relay (RELAY_URL + RELAY_SECRET)
const TG_TOKEN = env("TELEGRAM_BOT_TOKEN");
const TG_CHAT = env("TELEGRAM_CHAT_ID");
const TG_RELAY_URL = env("TELEGRAM_RELAY_URL").replace(/\/+$/, "");
const TG_RELAY_SECRET = env("RELAY_SECRET");
const TG_ON = !!((TG_RELAY_URL && TG_RELAY_SECRET) || (TG_TOKEN && TG_CHAT));

const EAPI = "https://eapi.binance.com";
const SPOT_HOSTS = ["https://api.binance.com", "https://data-api.binance.vision"];
const UNDERLYING = "BTCUSDT";
const SYMBOL_RE = /^BTC-\d{6}-\d+-[CP]$/;

const tradingEnabled = (): boolean => ["true", "1", "yes", "on"].includes(env("TRADING_ENABLED").toLowerCase());
const log = (...a: unknown[]): void => console.log(new Date().toISOString(), ...a);
const errMsg = (e: unknown): string => (e instanceof Error ? e.message : String(e));
const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));

// ───────────────────────── HF persistent store (app.py /api/render_store/<rules|journal|log>) ─────────────────────────
// Render ki apni disk ephemeral hai, isliye rules/journal/render_log HF Space ke /data (persistent volume) me jaate hain.
// Auth: header X-Store-Token. Space private ho to HF_ACCESS_TOKEN (Authorization: Bearer hf_...) bhi lagta hai.
let storeWarnedVolatile = false;
const storeState = { lastOkAt: 0, lastFailAt: 0, lastStatus: 0 as number, lastFail: "" };
async function storeCall(method: "GET" | "POST", name: "rules" | "journal" | "log", body?: Json, timeoutMs = 12000): Promise<BResult> {
  try { return await storeCallRaw(method, name, body, timeoutMs); }
  catch (e) { storeState.lastFailAt = Date.now(); storeState.lastFail = errMsg(e).slice(0, 100); throw e; }
}
async function storeCallRaw(method: "GET" | "POST", name: "rules" | "journal" | "log", body: Json | undefined, timeoutMs: number): Promise<BResult> {
  const headers: Record<string, string> = { "x-store-token": STORE_TOKEN, "content-type": "application/json" };
  if (HF_ACCESS) headers.authorization = `Bearer ${HF_ACCESS}`;
  const r = await fetch(`${STORE_URL}/api/render_store/${name}`, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(timeoutMs),
  });
  let data: Json = null;
  try { data = await r.json(); } catch { /* non-JSON (HF error page) */ }
  storeState.lastStatus = r.status;
  if (r.ok) storeState.lastOkAt = Date.now();
  else { storeState.lastFailAt = Date.now(); storeState.lastFail = `HTTP ${r.status} ${String(data?.msg ?? "").slice(0, 80)}`; }
  if (r.ok && data?.persistent === false && !storeWarnedVolatile) {
    storeWarnedVolatile = true;
    log("WARNING: HF /data mount nahi mila — store data ephemeral folder me ja raha hai (Space restart par udd jaayega)");
  }
  return { ok: r.ok && !!data && data.ok !== false, status: r.status, data };
}
// Same batch dobara bheje (retry) to app.py duplicate na likhe — batch ke content se stable id
const batchId = (rows: Json[]): string => createHash("sha1").update(JSON.stringify(rows)).digest("hex").slice(0, 20);

// ───────────────────────── render_log (HF store `log`, HOURLY batch write) ─────────────────────────
// Kya chal raha hai Render par — teen raste: browser>render (/secure), render>binance (bn()), render (engine/boot/shutdown).
//  • kind 'summary' : reads ki har-minute ki ek row (hop+src ke hisaab se: calls, fails, avg/max ms)
//  • kind 'fail'    : har fail alag row (same minute + same error = ek row, `calls` me ginti)
//  • kind 'order'   : order/cancel/panic/close/rule-exit
//  • kind 'event'   : boot / shutdown / IP-ban start
// Memory me jama hota hai, HF store me sirf har 1 ghante me (+ shutdown par) ek batch jaata hai. /tmp me backup file. Purani rows (30 din+) app.py hatata hai.
// Kabhi throw nahi karta, kisi request ko block nahi karta. API key / signature / passphrase kabhi log nahi hote.
const HOP_RB = "render>binance";
const HOP_BR = "browser>render";
const RLOG_FLUSH_MS = 2 * 60_000;   // har 2 minute me HF store par (pehle 60 min)
const RLOG_KEEP_DAYS = 30;
const RLOG_FILE = "/tmp/render_log.jsonl";
type RAgg = { ts: string; kind: string; hop: string; src: string; endpoint: string | null; calls: number; fails: number; ms_sum: number; ms_max: number; status: number | null; msg: string | null };
const rlogAgg = new Map<string, RAgg>();
const rlogRows: Json[] = [];
let rlogPending: Json[] = [];
let rlogBusy = false;
let rlogLastPrune = 0;
const srcStore = new AsyncLocalStorage<string>();
const minuteIso = (t = Date.now()): string => new Date(Math.floor(t / 60_000) * 60_000).toISOString();

function srcOfPath(path: string): string {
  const s = srcStore.getStore();
  if (s) return s;
  if (/\/(account|marginAccount)$/.test(path)) return "balance";
  if (path.endsWith("/position")) return "positions";
  if (path.endsWith("/openOrders")) return "orders";
  if (/\/(userTrades|historyOrders|bill|exerciseRecord)$/.test(path)) return "history";
  if (/\/(order|allOpenOrders)$/.test(path)) return "order";
  return "public";
}
function rlogCall(hop: string, src: string, endpoint: string, ms: number, ok: boolean, status: number | null, err?: unknown): void {
  try {
    const m = minuteIso();
    const k = `s|${m}|${hop}|${src}`;
    let a = rlogAgg.get(k);
    if (!a) { a = { ts: m, kind: "summary", hop, src, endpoint: null, calls: 0, fails: 0, ms_sum: 0, ms_max: 0, status: null, msg: null }; rlogAgg.set(k, a); }
    a.calls++; a.ms_sum += ms; if (ms > a.ms_max) a.ms_max = ms;
    if (!ok) {
      a.fails++;
      const em = String(err ?? "").replace(/signature=[0-9a-f]+/gi, "signature=***").slice(0, 200);
      const fk = `f|${m}|${hop}|${src}|${endpoint}|${status}|${em.slice(0, 80)}`;
      let f = rlogAgg.get(fk);
      if (!f) { f = { ts: m, kind: "fail", hop, src, endpoint, calls: 0, fails: 0, ms_sum: 0, ms_max: 0, status, msg: em }; rlogAgg.set(fk, f); }
      f.calls++; f.fails++; f.ms_sum += ms; if (ms > f.ms_max) f.ms_max = ms;
    }
    if (rlogAgg.size > 4000) { const first = rlogAgg.keys().next().value; if (first !== undefined) rlogAgg.delete(first); }
  } catch { /* logging kabhi kaam nahi bigadta */ }
}
function rlogRow(kind: "order" | "event", hop: string, src: string, endpoint: string | null, ok: boolean, msg: string, extra: Json = null): void {
  try {
    rlogRows.push({ ts: new Date().toISOString(), kind, hop, src, endpoint, calls: 1, fails: ok ? 0 : 1, avg_ms: null, max_ms: null, status: null, msg: String(msg).slice(0, 300), extra });
    if (rlogRows.length > 2000) rlogRows.splice(0, rlogRows.length - 2000);
  } catch { /* ignore */ }
}
function rlogFinalize(all = true): Json[] {
  const rows: Json[] = [];
  const cur = minuteIso();   // chalu minute ki rows tab tak rokte hain jab tak minute poora na ho (ek minute ki 2 rows na banein)
  for (const [k, a] of rlogAgg) {
    if (!all && a.ts >= cur) continue;
    rows.push({ service: "replay", ts: a.ts, kind: a.kind, hop: a.hop, src: a.src, endpoint: a.endpoint, calls: a.calls, fails: a.fails,
      avg_ms: a.calls ? Math.round(a.ms_sum / a.calls) : null, max_ms: Math.round(a.ms_max), status: a.status, msg: a.msg, extra: null });
    rlogAgg.delete(k);
  }
  for (const r of rlogRows.splice(0)) rows.push({ service: "replay", ...r });
  return rows;
}
async function rlogFlush(reason: string): Promise<void> {
  const fresh = rlogFinalize(reason === "shutdown");
  if (fresh.length) {
    try { appendFileSync(RLOG_FILE, fresh.map((r) => JSON.stringify(r)).join("\n") + "\n"); } catch { /* ephemeral disk */ }
    rlogPending.push(...fresh);
  }
  if (!rlogPending.length || rlogBusy) return;
  if (!STORE_ON) { rlogPending = []; return; }      // sirf /tmp file
  rlogBusy = true;
  try {
    while (rlogPending.length) {
      const batch = rlogPending.slice(0, 500);
      const r = await storeCall("POST", "log", { batch_id: batchId(batch), rows: batch });
      if (!r.ok) { log(`render_log store fail (${reason}):`, r.status, JSON.stringify(r.data ?? "").slice(0, 200)); break; }
      rlogPending.splice(0, batch.length);
    }
  } catch (e) { log(`render_log store error (${reason}):`, errMsg(e)); }
  finally { rlogBusy = false; if (rlogPending.length > 5000) rlogPending.splice(0, rlogPending.length - 5000); }
}

// ───────────────────────── Binance client ─────────────────────────
let timeOffset = 0;
let banUntil = 0;
type LastErr = { at: number; src: string; path: string; status: number; code: unknown; msg: string };
const stats = { calls: 0, errors: 0, started: Date.now(), lastCallAt: 0, lastErr: null as LastErr | null };
let posFailStreak = 0;     // engine ko lagatar kitni baar positions nahi mili
let rulesFailStreak = 0;   // rules HF store me lagatar kitni baar save fail hue
const envMissing = (): string[] => [
  !TRADE_TOKEN && "TRADE_TOKEN", !API_KEY && "OPT_API_KEY", !SECRET && "OPT_SECRET_KEY",
  !secureKey && "SECURE_PASSPHRASE", !STORE_ON && "HF_STORE_URL/HF_STORE_TOKEN",
].filter(Boolean) as string[];
const noteErr = (src: string, path: string, status: number, code: unknown, msg: unknown): void => {
  stats.lastErr = { at: Date.now(), src, path, status, code: code ?? null, msg: String(msg ?? "").slice(0, 300) };
};
const recentCmds: Json[] = [];   // /health me dikhne ke liye: last 10 order/cancel/close/rule events

function qs(params: Params): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== "") u.append(k, String(v));
  }
  return u.toString();
}

// 19-digit orderId/tradeId JS number mein precision kho dete hain — string bana do.
function parseBinance(text: string): Json {
  const fixed = text.replace(/"(orderId|tradeId|id)"\s*:\s*(\d{15,})/g, '"$1":"$2"');
  try { return JSON.parse(fixed); } catch { return { msg: text.slice(0, 300) }; }
}

async function syncTime(): Promise<void> {
  try {
    const r = await fetch(`${EAPI}/eapi/v1/time`, { signal: AbortSignal.timeout(8000) });
    const j = (await r.json()) as Json;
    if (j && typeof j.serverTime === "number") timeOffset = j.serverTime - Date.now();
  } catch (e) { log("syncTime fail:", errMsg(e)); }
}

async function bn(method: string, path: string, params: Params = {}, signed = false): Promise<BResult> {
  const src = srcOfPath(path);
  if (Date.now() < banUntil) {
    rlogCall(HOP_RB, src, path, 0, false, 429, "IP-ban active — request bheji nahi");
    noteErr(src, path, 429, -1003, `Binance IP-ban active (~${Math.ceil((banUntil - Date.now()) / 1000)}s baaki)`);
    return { ok: false, status: 429, data: { code: -1003, msg: `Binance IP-ban active (~${Math.ceil((banUntil - Date.now()) / 1000)}s baaki) — request bheji nahi` } };
  }
  if (signed && (!API_KEY || !SECRET)) {
    rlogCall(HOP_RB, src, path, 0, false, 500, "OPT_API_KEY / OPT_SECRET_KEY env set nahi");
    noteErr(src, path, 500, 0, "OPT_API_KEY / OPT_SECRET_KEY Render env mein set nahi hain");
    return { ok: false, status: 500, data: { code: 0, msg: "OPT_API_KEY / OPT_SECRET_KEY Render env mein set nahi hain" } };
  }
  let query = qs(params);
  if (signed) {
    query = qs({ ...params, recvWindow: 10000, timestamp: Date.now() + timeOffset });
    query += "&signature=" + createHmac("sha256", SECRET).update(query).digest("hex");
  }
  const headers: Record<string, string> = {};
  if (API_KEY) headers["X-MBX-APIKEY"] = API_KEY;
  stats.calls++;
  stats.lastCallAt = Date.now();
  const t0 = Date.now();
  try {
    const r = await fetch(`${EAPI}${path}${query ? "?" + query : ""}`, { method, headers, signal: AbortSignal.timeout(15000) });
    const data = parseBinance(await r.text());
    const ms = Date.now() - t0;
    if (!r.ok) {
      stats.errors++;
      noteErr(src, path, r.status, data?.code, data?.msg ?? `HTTP ${r.status}`);
      log(`BINANCE ERR ${method} ${path} -> HTTP ${r.status} ${JSON.stringify(data).slice(0, 250)}`);
      rlogCall(HOP_RB, src, path, ms, false, r.status, `${data?.code ?? ""} ${data?.msg ?? "HTTP " + r.status}`.trim());
      const wasBanned = Date.now() < banUntil;
      const m = /banned until (\d+)/.exec(String(data?.msg ?? ""));
      if (m) banUntil = Number(m[1]);
      else if (r.status === 418 || r.status === 429) banUntil = Date.now() + 60_000;
      if (!wasBanned && Date.now() < banUntil) {
        rlogRow("event", HOP_RB, "ban", path, false, `IP-ban shuru (HTTP ${r.status}) ~${Math.ceil((banUntil - Date.now()) / 1000)}s`, { until: banUntil });
        alertMail("Binance IP-BAN", `HTTP ${r.status} ${path}\nBan ~${Math.ceil((banUntil - Date.now()) / 1000)}s. Is dauran SL/Target exit orders bhi nahi ja payenge — open positions dekho!`, "binance-ban");
      }
    } else {
      rlogCall(HOP_RB, src, path, ms, true, r.status);
    }
    return { ok: r.ok, status: r.status, data };
  } catch (e) {
    stats.errors++;
    noteErr(src, path, 504, 0, `Binance request fail: ${errMsg(e)}`);
    rlogCall(HOP_RB, src, path, Date.now() - t0, false, 504, errMsg(e));
    return { ok: false, status: 504, data: { code: 0, msg: `Binance request fail: ${errMsg(e)}` } };
  }
}

// ───────────────────────── number / tick helpers ─────────────────────────
const fmt = (n: number): string => n.toFixed(8).replace(/\.?0+$/, "");
const decOf = (tick: number): number => {
  const s = fmt(tick);
  const i = s.indexOf(".");
  return i < 0 ? 0 : s.length - i - 1;
};
const floorTick = (x: number, tick: number): number => Number((Math.floor(x / tick + 1e-9) * tick).toFixed(decOf(tick)));
const ceilTick = (x: number, tick: number): number => Number((Math.ceil(x / tick - 1e-9) * tick).toFixed(decOf(tick)));
const num = (v: unknown): number => { const n = Number(v); return Number.isFinite(n) ? n : 0; };

let exCache: { ts: number; ticks: Map<string, number> } | null = null;
async function tickOf(symbol: string): Promise<number | null> {
  if (!exCache || Date.now() - exCache.ts > 10 * 60_000) {
    const r = await bn("GET", "/eapi/v1/exchangeInfo");
    if (r.ok && Array.isArray(r.data?.optionSymbols)) {
      const ticks = new Map<string, number>();
      for (const s of r.data.optionSymbols as Json[]) {
        const f = (s.filters as Json[] | undefined)?.find((x) => x.filterType === "PRICE_FILTER");
        if (s.symbol && f && num(f.tickSize) > 0) ticks.set(String(s.symbol), num(f.tickSize));
      }
      exCache = { ts: Date.now(), ticks };
    }
  }
  return exCache?.ticks.get(symbol) ?? null;
}

type Quote = { bid: number; ask: number; mark: number; low: number; high: number };
async function quoteOf(symbol: string): Promise<Quote> {
  const [t, m] = await Promise.all([
    bn("GET", "/eapi/v1/ticker", { symbol }),
    bn("GET", "/eapi/v1/mark", { symbol }),
  ]);
  const tr: Json = Array.isArray(t.data) ? t.data[0] : t.data;
  const mr: Json = Array.isArray(m.data) ? m.data[0] : m.data;
  return {
    bid: num(t.ok ? tr?.bidPrice : 0), ask: num(t.ok ? tr?.askPrice : 0),
    mark: num(m.ok ? mr?.markPrice : 0), low: num(m.ok ? mr?.lowPriceLimit : 0), high: num(m.ok ? mr?.highPriceLimit : 0),
  };
}

// Position band karne ka aggressive limit price (Binance ke price-limit band ke andar)
function closePrice(side: "SELL" | "BUY", q: Quote, tick: number, tries = 0): number {
  if (side === "SELL") {
    // tries 1-2: bid-3% | 3-5: bid-10% | 6+: Binance ka allowed sabse neeche price (kisi bhi bid par fill)
    if (tries >= 6 && q.low > 0) return Math.max(ceilTick(q.low, tick), tick);
    const f = tries >= 3 ? 0.90 : 0.97;
    const base = q.bid > 0 ? q.bid * f : q.mark > 0 ? q.mark * (tries >= 3 ? 0.5 : 0.7) : 0;
    if (base <= 0) return 0;
    let p = floorTick(base, tick);
    if (q.low > 0 && p < q.low) p = ceilTick(q.low, tick);
    return Math.max(p, tick);
  }
  const base = q.ask > 0 ? q.ask * 1.03 : q.mark > 0 ? q.mark * 1.3 : 0;
  if (base <= 0) return 0;
  let p = ceilTick(base, tick);
  if (q.high > 0 && p > q.high) p = floorTick(q.high, tick);
  return Math.max(p, tick);
}

async function placeLimit(symbol: string, side: string, qty: number, price: number, reduceOnly: boolean, cid?: string): Promise<BResult> {
  return bn("POST", "/eapi/v1/order", {
    symbol, side, type: "LIMIT", quantity: fmt(qty), price: fmt(price), timeInForce: "GTC",
    reduceOnly: reduceOnly ? "true" : undefined, newClientOrderId: cid, newOrderRespType: "RESULT",
  }, true);
}

type Pos = { symbol: string; qty: number; short: boolean };
async function loadPositions(): Promise<{ ok: boolean; map: Map<string, Pos>; err: string }> {
  const r = await bn("GET", "/eapi/v1/position", {}, true);
  const map = new Map<string, Pos>();
  if (!r.ok || !Array.isArray(r.data)) return { ok: false, map, err: String(r.data?.msg ?? "positions fail") };
  for (const p of r.data as Json[]) {
    const raw = num(p.quantity ?? p.qty);
    if (raw === 0 || !p.symbol) continue;
    map.set(String(p.symbol), { symbol: String(p.symbol), qty: Math.abs(raw), short: String(p.side ?? "").toUpperCase() === "SHORT" || raw < 0 });
  }
  return { ok: true, map, err: "" };
}

// ───────────────────────── alerts (Brevo email) ─────────────────────────
const lastAlert = new Map<string, number>();
const lastTg = new Map<string, number>();
const tgMask = (t: string): string => (TG_TOKEN ? t.split(TG_TOKEN).join("***") : t);
async function tgSend(text: string): Promise<void> {
  if (!TG_ON) return;
  const body = text.slice(0, 3900);
  for (let i = 0; i < 2; i++) {   // ek retry (network blip / 429)
    try {
      const r = (TG_RELAY_URL && TG_RELAY_SECRET)
        ? await fetch(`${TG_RELAY_URL}/send`, { method: "POST", headers: { "content-type": "application/json", "X-Relay-Secret": TG_RELAY_SECRET }, body: JSON.stringify({ text: body }), signal: AbortSignal.timeout(25000) })
        : await fetch(`https://api.telegram.org/bot${TG_TOKEN}/sendMessage`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ chat_id: TG_CHAT, text: body, disable_web_page_preview: true }), signal: AbortSignal.timeout(10000) });
      if (r.ok) return;
      log("telegram fail: HTTP", r.status, tgMask((await r.text().catch(() => "")).slice(0, 150)));
      if (r.status >= 400 && r.status < 500 && r.status !== 429) return;   // token/chat galat — retry bekaar
    } catch (e) { log("telegram error:", tgMask(errMsg(e))); }
    await sleep(1500);
  }
}
const istNow = (): string => new Date().toLocaleTimeString("en-IN", { timeZone: "Asia/Kolkata", hour12: true });
// har trade (order / cancel / close / rule add-remove) ka pass-fail Telegram par. Engine ke rule-exit ke alerts alertMail se jaate hain.
const TG_TRADE_LABEL: Record<string, string> = { place_order: "ORDER", cancel_order: "CANCEL", cancel_all: "CANCEL ALL", close_position: "CLOSE", add_rule: "RULE ADD", remove_rule: "RULE REMOVE" };
function tgTradeAlert(e: Json): void {
  try {
    const a = String(e?.action ?? "");
    if (e?.source === "engine" || !TG_TRADE_LABEL[a]) return;
    if (!TG_ON && !(BREVO_KEY && ALERT_TO)) return;   // koi channel configured nahi
    const ok = e.ok === true;
    const line2 = [e.side, e.qty != null ? `qty ${e.qty}` : "", e.price != null ? `@ ${e.price}` : "", e.reduce_only ? "(reduceOnly)" : ""].filter(Boolean).join(" ");
    const msg = String(e.msg ?? "").trim();
    const head = `${ok ? "✅" : "❌"} ${TG_TRADE_LABEL[a]} ${ok ? "OK" : "FAILED"}`;
    const lines = [head, e.symbol ? String(e.symbol) : "", line2, msg ? (ok ? msg : `Wajah: ${msg}`) : "", `🕐 ${istNow()} IST`].filter(Boolean);
    if (TG_ON) void tgSend(lines.join("\n"));
    emailSend(`${head}${e.symbol ? " " + String(e.symbol) : ""}`, lines.join("\n"));   // trade result par rate-limit nahi, har result ki email
  } catch (err) { log("tgTradeAlert error:", errMsg(err)); }
}
const ALERT_GAP_MS = 60_000;   // Telegram aur email dono: ek key par 60s me ek (spam na ho)
// Email (Brevo). key diya to usi key par ALERT_GAP_MS ke andar dobara nahi jaata; key nahi to har baar jaata hai (trade result).
function emailSend(subject: string, body: string, key?: string): void {
  if (!BREVO_KEY || !ALERT_TO) return;
  if (key) {
    const now = Date.now();
    if (now - (lastAlert.get(key) ?? 0) < ALERT_GAP_MS) return;
    lastAlert.set(key, now);
  }
  fetch("https://api.brevo.com/v3/smtp/email", {
    method: "POST",
    headers: { "api-key": BREVO_KEY, "content-type": "application/json", accept: "application/json" },
    body: JSON.stringify({
      sender: { name: "My-engine", email: ALERT_FROM }, to: [{ email: ALERT_TO }],
      subject: `[My-engine] ${subject}`, htmlContent: `<pre style="font-family:monospace">${body.replace(/[<>&]/g, "")}</pre>`,
    }),
    signal: AbortSignal.timeout(10000),
  }).catch((e) => log("brevo fail:", errMsg(e)));
}
function alertMail(subject: string, body: string, key = subject): void {
  log("ALERT:", subject, "|", body);
  if (TG_ON) {   // Telegram: har key par 60s me ek (error repeat par spam na ho)
    const tn = Date.now();
    if (tn - (lastTg.get(key) ?? 0) >= ALERT_GAP_MS) { lastTg.set(key, tn); void tgSend(`🔔 ${subject}\n${body}\n🕐 ${istNow()} IST`); }
  }
  emailSend(subject, body, key);
}

// ───────────────────────── rules store (memory + HF store + /tmp) ─────────────────────────
type RuleStatus = "active" | "triggered" | "done" | "error" | "cancelled";
type Rule = {
  id: string; symbol: string; side: "LONG"; entry_qty: number; entry_price: number;
  sl: number | null; tp: number | null; trail: number | null;
  status: RuleStatus; created_at: number; high: number; note: string; error: string | null;
  exit_order_id: string | null; exit_at: number; exit_tries: number; trigger_reason: string | null;
  hi_set?: boolean;   // trailing `high` fill ke baad ke asli bid se set ho chuka? (false = abhi set karna hai)
  hs?: number;        // `high` ki last saved value — bada move ho to turant save (restart par stale high na rahe)
  breach?: number;    // SL/trailing lagatar kitni baar touch hua (confirmation)
  nq?: number;        // lagatar kitni baar bid+mark dono nahi mile
};
let rules: Rule[] = [];
const LOCAL_FILE = "/tmp/opt_rules.json";
let saveTimer: ReturnType<typeof setTimeout> | null = null;
// rulesSynced: HF store se ek baar load ho chuka (ya store band hai). Isse pehle store me save NAHI hota —
// warna load fail hone par khaali list purane rules ko overwrite kar deti.
let rulesSynced = !STORE_ON;
let rulesDirty = false;      // store me bhejna baaki hai (fail hua ya abhi bhejna hai)
let rulesSaving = false;

// (2026-10-01) EGRESS BACHAT — pehle engineTick har 5s persistSoon() se rules ki poori list HF ko POST karta tha, chahe kuch
// badla ho ya nahi. Ab signature (rules ka content) badle tabhi POST hota hai:
//   • kuch bhi badla nahi            -> koi POST nahi (aur /tmp file bhi dobara nahi likhi jaati)
//   • sirf trailing `high` badla     -> max 30s me ek POST (RULES_HIGH_SAVE_MS)
//   • status/SL/TP/note/exit badla   -> turant (1s debounce)
// Shutdown par saveRulesNow(true) throttle ignore karke aakhri state save kar deta hai.
const RULES_HIGH_SAVE_MS = 30_000;
const rulesSig = (withHigh: boolean): string => JSON.stringify(withHigh ? rules : rules.map((r) => ({ ...r, high: 0 })));
let rulesFileSig = "";       // /tmp file me jo last likha
let rulesSavedFull = "";     // HF store me jo last gaya (high samet)
let rulesSavedNoHigh = "";   // wahi, high ke bina
let rulesSavedAt = 0;
let rulesInflightSig = "";   // abhi POST ho raha hai

async function saveRulesNow(force = false): Promise<void> {
  const keep = rules.filter((r) => r.status === "active" || r.status === "triggered").concat(
    rules.filter((r) => r.status !== "active" && r.status !== "triggered").slice(-50));
  rules = keep;
  const sigFull = rulesSig(true);
  if (sigFull !== rulesFileSig) {
    try { writeFileSync(LOCAL_FILE, JSON.stringify(rules)); rulesFileSig = sigFull; } catch { /* ephemeral disk */ }
  }
  if (!STORE_ON || !rulesSynced) return;
  if (sigFull === rulesSavedFull) { rulesDirty = false; return; }       // store me pehle se yahi hai
  if (sigFull === rulesInflightSig) return;                             // yahi abhi ja raha hai
  if (!force && rulesSig(false) === rulesSavedNoHigh && Date.now() - rulesSavedAt < RULES_HIGH_SAVE_MS) return;   // sirf trailing high badla
  rulesDirty = true;
  if (rulesSaving) return;             // chalti hui save loop latest `rules` hi bhejegi
  rulesSaving = true;
  try {
    while (rulesDirty) {
      rulesDirty = false;
      const snapFull = rulesSig(true), snapNo = rulesSig(false);
      rulesInflightSig = snapFull;
      try {
        const r = await storeCall("POST", "rules", { data: rules });
        if (!r.ok) { rulesDirty = true; log("store rules save fail:", r.status, JSON.stringify(r.data ?? "").slice(0, 200)); rulesSaveFailed(`HTTP ${r.status} ${JSON.stringify(r.data ?? "").slice(0, 120)}`); break; }
        rulesSavedFull = snapFull; rulesSavedNoHigh = snapNo; rulesSavedAt = Date.now();
        rulesSaveOk();
      } catch (e) { rulesDirty = true; log("store rules save error:", errMsg(e)); rulesSaveFailed(errMsg(e)); break; }
    }
  } finally { rulesSaving = false; rulesInflightSig = ""; }
}
function rulesSaveFailed(why: string): void {
  rulesFailStreak++;
  if (rulesFailStreak === 3 || rulesFailStreak % 60 === 0) {
    alertMail("Rules SAVE FAIL (HF store)", `${rulesFailStreak} baar lagatar save fail: ${why}\nRender restart hua to SL/Target rules chale jayenge!`, "rules-save");
  }
}
function rulesSaveOk(): void {
  if (rulesFailStreak >= 3) alertMail("Rules save wapas theek", `${rulesFailStreak} fail ke baad HF store me save ho gaye.`, "rules-save-ok");
  rulesFailStreak = 0;
}
function persistSoon(): void {
  if (saveTimer) return;
  saveTimer = setTimeout(() => { saveTimer = null; void saveRulesNow(); }, 1000);
}
// Store down tha to 30s me dobara koshish (rules ka koi change /tmp me hai par store me nahi)
setInterval(() => { if (rulesDirty && !rulesSaving) void saveRulesNow(); }, 30_000).unref();

// Boot par store band mila: rules /tmp se chalte rahe, store wapas aate hi merge (store wale jo memory me nahi, jod do)
async function rulesResync(): Promise<void> {
  while (!rulesSynced) {
    await sleep(15_000);
    try {
      const r = await storeCall("GET", "rules");
      if (!r.ok) continue;
      const stored: Rule[] = Array.isArray(r.data?.data) ? (r.data.data as Rule[]) : [];
      const have = new Set(rules.map((x) => x.id));
      for (const x of stored) if (!have.has(x.id)) rules.push(x);
      rulesSynced = true;
      log(`rules HF store se late merge: store=${stored.length} total=${rules.length}`);
      persistSoon();
    } catch { /* agli baar */ }
  }
}
async function loadRules(): Promise<void> {
  if (STORE_ON) {
    for (let i = 0; i < 3; i++) {
      try {
        const r = await storeCall("GET", "rules", undefined, 8000);
        if (r.ok) {
          rulesSynced = true;
          if (Array.isArray(r.data?.data)) {
            rules = r.data.data as Rule[]; log("rules HF store se load:", rules.length);
            rulesSavedFull = rulesSig(true); rulesSavedNoHigh = rulesSig(false); rulesSavedAt = Date.now(); rulesFileSig = rulesSavedFull;   // store me yahi hai — dobara POST nahi
            return;
          }
          log("HF store me abhi rules nahi — /tmp check hoga");
          break;
        }
        log("store load fail:", r.status);
      } catch (e) { log("store load error:", errMsg(e)); }
      await sleep(1500 * (i + 1));
    }
  }
  try { rules = JSON.parse(readFileSync(LOCAL_FILE, "utf8")) as Rule[]; log("rules local file se load:", rules.length); } catch { rules = []; }
  if (STORE_ON && !rulesSynced) { log("WARNING: HF store abhi reachable nahi — rules /tmp se; store aate hi merge hoga"); void rulesResync(); }
}

// ───────────────────────── trade journal (HF store `journal` + /tmp fallback) ─────────────────────────
// Har order/cancel/cancel-all/panic/close + rules-engine exit ki ek row. Kabhi throw nahi karta, order ko block nahi karta.
// HF store fail ho to memory queue mein rehta hai (max 500) aur har 30s retry hota hai; /tmp/opt_journal.jsonl hamesha likhi jaati hai.
const JOURNAL_FILE = "/tmp/opt_journal.jsonl";
const journalQ: Json[] = [];
let journalBusy = false;
async function journalFlush(): Promise<void> {
  if (journalBusy || !journalQ.length || !STORE_ON) return;
  journalBusy = true;
  const batch = journalQ.slice(0, 100);
  try {
    const r = await storeCall("POST", "journal", { batch_id: batchId(batch), rows: batch });
    if (r.ok) journalQ.splice(0, batch.length);
    else log("journal store fail:", r.status, JSON.stringify(r.data ?? "").slice(0, 200));
  } catch (e) { log("journal store error:", errMsg(e)); }
  finally { journalBusy = false; }
}
function journal(entry: Json): void {
  try {
    const row = { ts: new Date().toISOString(), ...entry };
    tgTradeAlert(entry);
    recentCmds.push({ at: Date.now(), source: entry?.source ?? null, action: entry?.action ?? null, ok: entry?.ok === true, msg: String(entry?.msg ?? "").slice(0, 300),
      symbol: entry?.symbol ?? null, side: entry?.side ?? null, qty: entry?.qty ?? null, price: entry?.price ?? null });
    if (recentCmds.length > 10) recentCmds.splice(0, recentCmds.length - 10);
    rlogRow("order", entry?.source === "engine" ? "render" : HOP_BR, String(entry?.action ?? "order"), entry?.symbol ?? null, entry?.ok === true,
      `${entry?.side ?? ""} ${entry?.qty ?? ""}${entry?.price != null ? " @ " + entry.price : ""} ${entry?.msg ?? ""}`.trim(),
      { source: entry?.source ?? null, symbol: entry?.symbol ?? null, side: entry?.side ?? null, qty: entry?.qty ?? null, price: entry?.price ?? null, client_order_id: entry?.client_order_id ?? null });
    try { appendFileSync(JOURNAL_FILE, JSON.stringify(row) + "\n"); } catch { /* ephemeral disk */ }
    if (STORE_ON) {
      journalQ.push(row);
      if (journalQ.length > 500) journalQ.splice(0, journalQ.length - 500);
      void journalFlush();
    }
  } catch (e) { log("journal error:", errMsg(e)); }
}
function journalCmd(source: string, cmd: Json, ack: Json, cid: string | null): void {
  const d: Json = ack?.data && typeof ack.data === "object" ? ack.data : {};
  const pn = (v: unknown): number | null => { const n = Number(v); return Number.isFinite(n) && v !== null && v !== "" ? n : null; };
  journal({
    source, action: String(cmd?.action ?? ack?.action ?? ""), symbol: cmd?.symbol ?? d.symbol ?? null,
    side: cmd?.side ?? (cmd?.action === "close_position" ? "SELL" : null) ?? d.side ?? null,
    qty: pn(cmd?.quantity ?? ack?.close_qty ?? d.quantity), price: pn(cmd?.price ?? ack?.close_price ?? d.price),
    reduce_only: cmd?.reduceOnly === true || cmd?.reduceOnly === "true" || null,
    ok: !!ack?.ok, msg: String(ack?.msg ?? "").slice(0, 500), idem_key: cmd?.idem_key ?? null, client_order_id: cid,
    response: ack?.data ?? null,
  });
}
setInterval(() => { void journalFlush(); }, 30_000).unref();

const ruleView = (r: Rule): Json => ({ ...r, sl_price: r.sl, target_price: r.tp, trailing_points: r.trail });

// ───────────────────────── rules engine (SL / Target / Trailing) ─────────────────────────
// Har 5s: positions ek baar + active rules ke symbols ka bid. Data na mile to KABHI trigger nahi.
// Exit = reduceOnly aggressive SELL limit; 20s mein position band na ho to cancel + naya price, max 6 try.
const EXIT_RETRY_MS = 20_000;
const EXIT_MAX_TRIES = 6;          // itne try ke baad alert + aur aggressive price, par rule band NAHI hota
const SLOW_RETRY_MS = 60_000;      // EXIT_MAX_TRIES ke baad har 60s me retry
const EXIT_HARD_MAX = 60;          // ~1 ghanta lagatar fail ho tab hi rule 'error' (manual close)
const BREACH_CONFIRM = 2;          // SL/Trailing ko itne lagatar tick (5s) touch hona chahiye (blip se bachne ke liye)
let engineBusy = false;
let lastTickAt = 0;
let busySince = 0;
let lastUnprotCheck = 0;
const lastUnprot = new Map<string, number>();
const excCount = new Map<string, number>();
const UNPROT_ALERT = !["false", "0", "no", "off"].includes(env("UNPROTECTED_ALERT", "true").toLowerCase());
const UNPROT_GAP_MS = 30 * 60_000;
const CONF_NOTE = "SL/Trailing touch hua — confirm ke liye 1 tick wait";
const getQuote = (qc: Map<string, Promise<Quote>>, symbol: string): Promise<Quote> => {
  let pr = qc.get(symbol);
  if (!pr) { pr = quoteOf(symbol); qc.set(symbol, pr); }   // ek tick me ek symbol ka quote ek hi baar (API weight bachat)
  return pr;
};

// Long position hai par koi live rule nahi — (restart / rule save fail / rule 'error') — alert
function checkUnprotected(pos: Map<string, Pos>, live: Rule[]): void {
  if (!UNPROT_ALERT) return;
  const now = Date.now();
  for (const p of pos.values()) {
    if (p.short) continue;
    if (live.some((r) => r.symbol === p.symbol)) continue;
    if (now - (lastUnprot.get(p.symbol) ?? 0) < UNPROT_GAP_MS) continue;
    lastUnprot.set(p.symbol, now);
    alertMail(`Position BINA SL ${p.symbol}`, `qty ${fmt(p.qty)} open hai par koi live SL/Target/Trailing rule nahi hai.\n(Render restart ya rule save fail / rule error ho sakta hai.) Rule dobara lagao ya position close karo.\n(Ye alert band karna ho to env UNPROTECTED_ALERT=false)`, `unprot-${p.symbol}`);
  }
}

async function cancelSymbolOrders(symbol: string): Promise<void> {
  const oo = await bn("GET", "/eapi/v1/openOrders", { symbol }, true);
  if (!oo.ok || !Array.isArray(oo.data)) return;
  for (const o of oo.data as Json[]) {
    if (o.orderId) await bn("DELETE", "/eapi/v1/order", { symbol, orderId: String(o.orderId) }, true);
  }
}

async function sendExit(r: Rule, qty: number): Promise<void> {
  r.exit_tries++;
  r.exit_at = Date.now();
  await cancelSymbolOrders(r.symbol);
  const fresh = await loadPositions();   // cancel ke baad taza qty — partial fill hua ho to reduceOnly reject na ho
  if (fresh.ok) {
    const cur = fresh.map.get(r.symbol);
    if (!cur || cur.qty <= 0) { r.note = "exit se pehle position band mili"; return; }
    qty = Math.min(qty, cur.qty);
  }
  const tick = (await tickOf(r.symbol)) ?? 5;
  const q = await quoteOf(r.symbol);
  const price = closePrice("SELL", q, tick, r.exit_tries);
  if (price <= 0) { r.error = "bid/mark price nahi mila — exit retry hoga"; alertMail(`Exit price nahi mila ${r.symbol}`, `try ${r.exit_tries}: bid/mark price nahi mila, exit order nahi gaya.\nPosition open hai!`, `exitfail-${r.id}`); return; }
  const cid = "rx" + createHash("sha1").update(`${r.id}|${Date.now()}`).digest("hex").slice(0, 20);
  const res = await placeLimit(r.symbol, "SELL", qty, price, true, cid);
  journal({ source: "engine", action: "rule_exit", symbol: r.symbol, side: "SELL", qty, price, reduce_only: true, ok: res.ok,
    msg: res.ok ? `rule ${r.id} exit try ${r.exit_tries} (${r.trigger_reason ?? ""})` : String(res.data?.msg ?? "").slice(0, 300),
    idem_key: null, client_order_id: cid, response: res.data });
  if (res.ok) {
    r.exit_order_id = res.data?.orderId != null ? String(res.data.orderId) : null;
    r.error = null;
    r.note = `exit bheja: SELL ${fmt(qty)} @ ${fmt(price)} (try ${r.exit_tries})`;
  } else {
    r.error = `exit order fail: ${String(res.data?.msg ?? JSON.stringify(res.data)).slice(0, 200)}`;
    alertMail(`Exit order REJECT ${r.symbol}`, `try ${r.exit_tries}/${EXIT_MAX_TRIES}: ${r.error}\nPosition abhi open hai!`, `exitfail-${r.id}`);
  }
  log(`[rule ${r.id}] ${r.note || r.error}`);
}

async function handleRule(r: Rule, pos: Map<string, Pos>, exiting: Set<string>, qc: Map<string, Promise<Quote>>): Promise<void> {
  const p = pos.get(r.symbol);

  if (r.status === "triggered") {
    if (!p || p.qty <= 0) {
      r.status = "done"; r.note = "position band ho gayi (exit fill)"; r.error = null;
      alertMail(`Rule DONE ${r.symbol}`, `${r.trigger_reason}\nPosition band ho gayi.`, `done-${r.id}`);
      for (const o of rules) {
        if (o !== r && o.symbol === r.symbol && o.status === "active") { o.status = "done"; o.note = "position dusre rule se band hui"; }
      }
      return;
    }
    if (Date.now() - r.exit_at >= (r.exit_tries >= EXIT_MAX_TRIES ? SLOW_RETRY_MS : EXIT_RETRY_MS)) {
      if (r.exit_tries >= EXIT_HARD_MAX) {
        r.status = "error"; r.error = r.error || `exit ${EXIT_HARD_MAX} baar try kiya, position abhi bhi open — MANUAL close karo`;
        alertMail(`Rule ERROR ${r.symbol}`, `${r.error}\nPosition abhi bhi open hai!`, `err-${r.id}`);
        return;
      }
      if (r.exit_tries === EXIT_MAX_TRIES) {
        alertMail(`Exit ${EXIT_MAX_TRIES} try me fill nahi ${r.symbol}`, `Position abhi open hai. Bot haar nahi maanega: ab har 60s me sabse aggressive price par retry karega. Chaho to khud bhi CLOSE/PANIC karo.`, `giveup-${r.id}`);
      }
      await sendExit(r, p.qty);
    }
    return;
  }

  // status === "active"
  if (exiting.has(r.symbol)) return;   // isi symbol ka dusra rule exit kar raha hai
  if (!p || p.qty <= 0) {
    // Entry order shayad abhi fill nahi hua — open BUY order ho to wait
    const oo = await bn("GET", "/eapi/v1/openOrders", { symbol: r.symbol }, true);
    if (!oo.ok) return;
    const buyOpen = Array.isArray(oo.data) && (oo.data as Json[]).some((o) => String(o.side).toUpperCase() === "BUY");
    if (buyOpen) { r.note = "entry fill ka wait"; return; }
    if (Date.now() - r.created_at > 60_000) { r.status = "done"; r.note = "koi position/entry order nahi mila"; }
    return;
  }
  if (p.short) { r.status = "error"; r.error = "SHORT position — rule sirf LONG ke liye hai"; return; }

  const q = await getQuote(qc, r.symbol);
  const px = q.bid > 0 ? q.bid : q.mark;   // bid na ho (0DTE / expiry ke paas) to mark se SL check — pehle yahan return ho jata tha
  if (!(px > 0)) {
    r.nq = (r.nq ?? 0) + 1;
    if (r.nq === 3 || r.nq % 24 === 0) alertMail(`Quote nahi mil raha ${r.symbol}`, `${r.nq} tick se bid aur mark dono nahi mile — SL/Target abhi kaam nahi kar rahe! Position dekho.`, `noq-${r.id}`);
    return;
  }
  if ((r.nq ?? 0) >= 3) alertMail(`Quote wapas aa gaya ${r.symbol}`, `${r.nq} tick ke baad bid/mark mil gaye, SL/Target phir se chalu.`, `noq-ok-${r.id}`);
  r.nq = 0;
  if (r.trail) {
    if (r.hi_set === false) { r.high = px; r.hs = px; r.hi_set = true; }   // high fill ke baad ke asli price se shuru (limit price se nahi)
    else {
      const cand = q.mark > 0 ? Math.min(px, q.mark * 1.15) : px;   // mark se 15%+ upar ka bid = blip, high ko utha nahi sakta
      if (cand > r.high) r.high = cand;
      if (r.high - (r.hs ?? 0) >= r.trail / 2) r.hs = r.high;      // bada move: rules turant save (restart par stale high na rahe)
    }
  }
  const levels: number[] = [];
  if (r.sl) levels.push(r.sl);
  if (r.trail) levels.push(r.high - r.trail);
  const stop = levels.length ? Math.max(...levels) : null;
  let reason: string | null = null;
  if (stop !== null && px <= stop) {
    const deep = px <= stop * 0.95;   // crash: confirmation ka wait nahi
    r.breach = (r.breach ?? 0) + 1;
    if (r.breach < BREACH_CONFIRM && !deep) { r.note = CONF_NOTE; return; }
    reason = `SL/Trailing hit — ${q.bid > 0 ? "bid" : "mark(bid nahi)"} ${fmt(px)} <= ${fmt(stop)}`;
  } else {
    r.breach = 0;
    if (r.note === CONF_NOTE) r.note = "";
    if (r.tp && q.bid >= r.tp) reason = `Target hit — bid ${fmt(q.bid)} >= ${fmt(r.tp)}`;
  }
  if (!reason) return;

  r.status = "triggered"; r.trigger_reason = `${r.symbol}: ${reason}`; r.exit_tries = 0; r.exit_at = 0;
  exiting.add(r.symbol);
  alertMail(`Rule TRIGGERED ${r.symbol}`, r.trigger_reason, `trg-${r.id}`);
  await sendExit(r, p.qty);
}

async function engineTick(): Promise<void> {
  const now = Date.now();
  const liveN = rules.filter((r) => r.status === "active" || r.status === "triggered").length;
  if (liveN && lastTickAt && now - lastTickAt > 30_000) {
    alertMail("Engine ruka tha", `~${Math.round((now - lastTickAt) / 1000)}s tak engine tick nahi chala (Render sleep/freeze?). Is dauran SL/Target nahi chale — positions dekho!`, "engine-gap");
  }
  lastTickAt = now;
  if (engineBusy && busySince && now - busySince > 60_000) {
    alertMail("Engine atka hua", `Ek tick ${Math.round((now - busySince) / 1000)}s se chal raha hai — SL/Target der se check ho rahe hain.`, "engine-stuck");
  }
  return srcStore.run("engine", engineTickInner);
}
async function engineTickInner(): Promise<void> {
  if (engineBusy) return;
  const live = rules.filter((r) => r.status === "active" || r.status === "triggered");
  if (!live.length) {
    // koi live rule nahi — har 5 min me ek baar dekho ki kahin bina-SL position to nahi (rules kho gaye ho sakte hain)
    if (UNPROT_ALERT && Date.now() - lastUnprotCheck >= 5 * 60_000) {
      lastUnprotCheck = Date.now();
      engineBusy = true; busySince = Date.now();
      try { const pos = await loadPositions(); if (pos.ok) checkUnprotected(pos.map, live); }
      finally { engineBusy = false; }
    }
    return;
  }
  engineBusy = true; busySince = Date.now();
  try {
    const pos = await loadPositions();
    if (!pos.ok) {
      log("engine: positions nahi mili, is tick skip:", pos.err);
      posFailStreak++;
      if (posFailStreak === 3 || (posFailStreak > 3 && posFailStreak % 24 === 0)) {
        alertMail("Engine: positions nahi mil rahi", `${posFailStreak} baar lagatar fail (${live.length} live rule): ${String(pos.err).slice(0, 150)}\nSL/Target abhi kaam nahi kar rahe!`, "pos-fail");
      }
      return;
    }
    if (posFailStreak >= 3) alertMail("Engine positions wapas aa gayi", `${posFailStreak} fail ke baad theek. SL/Target phir se chalu.`, "pos-ok");
    posFailStreak = 0;
    checkUnprotected(pos.map, live);
    const exiting = new Set(live.filter((r) => r.status === "triggered").map((r) => r.symbol));
    const qc = new Map<string, Promise<Quote>>();
    for (const r of live) {
      try { await handleRule(r, pos.map, exiting, qc); excCount.delete(r.id); }
      catch (e) {
        // ek transient exception se rule hamesha ke liye band nahi — 5 lagatar par hi 'error'
        const n = (excCount.get(r.id) ?? 0) + 1; excCount.set(r.id, n);
        log(`rule ${r.id} exception ${n}/5: ${errMsg(e)}`);
        if (n >= 5) {
          r.status = "error"; r.error = errMsg(e);
          alertMail(`Rule ERROR ${r.symbol}`, `${r.error}\nPosition ko manual dekho!`, `err-${r.id}`);
        }
      }
    }
    persistSoon();
  } finally { engineBusy = false; }
}

// ───────────────────────── PANIC ─────────────────────────
async function doPanic(): Promise<Json> {
  const notes: string[] = [];
  const closes: Json[] = [];
  let n = 0;
  for (const r of rules) if (r.status === "active" || r.status === "triggered") { r.status = "cancelled"; r.note = "panic"; n++; }
  notes.push(`rules band: ${n}`);
  persistSoon();

  const ca = await bn("DELETE", "/eapi/v1/allOpenOrdersByUnderlying", { underlying: UNDERLYING }, true);
  const cancelOk = ca.ok;
  if (!cancelOk) notes.push(`cancel-all fail: ${String(ca.data?.msg ?? "")}`);
  await sleep(400);

  const pos = await loadPositions();
  if (!pos.ok) notes.push(`positions nahi mili: ${pos.err}`);
  for (const p of pos.map.values()) {
    const side = p.short ? "BUY" : "SELL";
    try {
      const tick = (await tickOf(p.symbol)) ?? 5;
      const price = closePrice(side, await quoteOf(p.symbol), tick);
      if (price <= 0) { closes.push({ symbol: p.symbol, ok: false, msg: "price nahi mila" }); continue; }
      const cid = "pn" + createHash("sha1").update(`${p.symbol}|${Date.now()}`).digest("hex").slice(0, 20);
      const res = await placeLimit(p.symbol, side, p.qty, price, true, cid);
      closes.push({ symbol: p.symbol, side, quantity: p.qty, price, ok: res.ok, msg: res.ok ? "sent" : String(res.data?.msg ?? "") });
    } catch (e) { closes.push({ symbol: p.symbol, ok: false, msg: errMsg(e) }); }
  }
  const ok = cancelOk && pos.ok && closes.every((c) => c.ok);
  alertMail(`PANIC ${ok ? "OK" : "FAILED"}`, `cancel_ok=${cancelOk}\ncloses=${JSON.stringify(closes)}\nnotes=${notes.join("; ")}`, "panic");
  return { ok, cancel_ok: cancelOk, closes, notes };
}

// ───────────────────────── HTTP helpers ─────────────────────────
function send(res: ServerResponse, status: number, body: Json, cors = false): void {
  const headers: Record<string, string> = { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" };
  if (cors) { headers["access-control-allow-origin"] = "*"; headers["access-control-allow-headers"] = "*"; }
  res.writeHead(status, headers);
  res.end(JSON.stringify(body));
}
function sendB(res: ServerResponse, r: BResult): void {
  if (r.ok) return send(res, 200, r.data);
  send(res, r.status >= 400 ? r.status : 502, { ok: false, code: r.data?.code ?? 0, msg: r.data?.msg ?? "Binance error", data: r.data });
}
function readBody(req: IncomingMessage): Promise<Json> {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks: Buffer[] = [];
    req.on("data", (c: Buffer) => {
      size += c.length;
      if (size > 65536) { reject(new Error("body too large")); req.destroy(); return; }
      chunks.push(c);
    });
    req.on("end", () => {
      const t = Buffer.concat(chunks).toString("utf8");
      if (!t) return resolve({});
      try { resolve(JSON.parse(t)); } catch { reject(new Error("bad json body")); }
    });
    req.on("error", reject);
  });
}
function tokenOk(got: string): boolean {
  if (!TRADE_TOKEN || !got) return false;
  const a = createHash("sha256").update(got).digest();
  const b = createHash("sha256").update(TRADE_TOKEN).digest();
  return timingSafeEqual(a, b);
}
function authOk(req: IncomingMessage): boolean {
  return tokenOk(String(req.headers["x-trade-token"] ?? ""));
}
const posNum = (v: unknown): number | null => { const n = Number(v); return Number.isFinite(n) && n > 0 ? n : null; };

// SIRF public market-data — signed/private path yahan se kabhi forward nahi hote
const PUBLIC_SPOT = /^\/api\/v3\/(time|ping|klines|uiKlines|ticker\/price|ticker\/24hr|ticker\/bookTicker|exchangeInfo|depth|trades|avgPrice)$/;
const PUBLIC_EAPI = /^\/eapi\/v1\/(time|ping|exchangeInfo|ticker|mark|depth|index|klines|trades|openInterest|exerciseHistory)$/;

async function forwardPublic(url: URL, res: ServerResponse): Promise<void> {
  const path = url.pathname;
  if (url.searchParams.has("signature") || url.searchParams.has("timestamp")) {
    return send(res, 403, { ok: false, msg: "signed calls generic forward se allowed nahi — /trade/... use karo" }, true);
  }
  if (PUBLIC_EAPI.test(path)) {
    const r = await bn("GET", path, Object.fromEntries(url.searchParams.entries()), false);
    if (r.ok) return send(res, 200, r.data, true);
    return send(res, r.status >= 400 ? r.status : 502, r.data, true);
  }
  if (PUBLIC_SPOT.test(path)) {
    let last: { status: number; body: string } = { status: 502, body: '{"msg":"spot unreachable"}' };
    for (const host of SPOT_HOSTS) {
      const t0 = Date.now();
      try {
        stats.calls++;
        const r = await fetch(`${host}${path}${url.search}`, { signal: AbortSignal.timeout(15000) });
        const body = await r.text();
        rlogCall(HOP_RB, "public", "spot" + path, Date.now() - t0, r.ok, r.status, r.ok ? "" : body.slice(0, 120));
        if (r.ok) { res.writeHead(200, { "content-type": "application/json", "access-control-allow-origin": "*" }); return void res.end(body); }
        last = { status: r.status, body };
      } catch (e) { rlogCall(HOP_RB, "public", "spot" + path, Date.now() - t0, false, 504, errMsg(e)); last = { status: 504, body: JSON.stringify({ msg: errMsg(e) }) }; }
    }
    res.writeHead(last.status, { "content-type": "application/json", "access-control-allow-origin": "*" });
    return void res.end(last.body);
  }
  send(res, 403, { ok: false, msg: "ye path forward allowed nahi" }, true);
}

// ───────────────────────── shared trade helpers (HTTP route + /secure dono use karte hain) ─────────────────────────
type Chk = { err?: { status: number; msg: string }; r?: BResult };
async function placeOrderChecked(symbol: string, sideRaw: string, qtyRaw: unknown, priceRaw: unknown, reduceOnly: boolean, cidRaw?: unknown): Promise<Chk> {
  const side = String(sideRaw ?? "").toUpperCase();
  const qty = posNum(qtyRaw);
  const price = posNum(priceRaw);
  if (!SYMBOL_RE.test(symbol)) return { err: { status: 400, msg: "symbol galat (BTC-YYMMDD-STRIKE-C/P chahiye)" } };
  if (side !== "BUY" && side !== "SELL") return { err: { status: 400, msg: "side BUY ya SELL hi" } };
  if (!qty || !price) return { err: { status: 400, msg: "quantity/price sahi nahi" } };
  if (side === "SELL" && !reduceOnly) return { err: { status: 403, msg: "SELL sirf reduceOnly (position close) allowed hai" } };
  if (side === "BUY" && !tradingEnabled()) return { err: { status: 403, msg: "TRADING_ENABLED=false — naye BUY orders band hain" } };
  // MAX_ORDER_* sirf naye BUY (exposure badhane wale) par — reduceOnly SELL (close) ko kabhi nahi rokte
  if (side === "BUY" && qty > MAX_ORDER_QTY) return { err: { status: 403, msg: `qty ${qty} > MAX_ORDER_QTY ${MAX_ORDER_QTY}` } };
  if (side === "BUY" && qty * price > MAX_ORDER_USDT) return { err: { status: 403, msg: `order value ${(qty * price).toFixed(2)} > MAX_ORDER_USDT ${MAX_ORDER_USDT}` } };
  const cid = typeof cidRaw === "string" && /^[\w-]{1,36}$/.test(cidRaw) ? cidRaw : undefined;
  const r = await placeLimit(symbol, side, qty, price, reduceOnly, cid);
  log(`order ${side} ${symbol} qty=${qty} px=${price} ro=${reduceOnly} -> ${r.ok ? "OK" : JSON.stringify(r.data)}`);
  return { r };
}

// SL/Target/Trailing ki sanity — galat levels se fill hote hi exit na ho. Order bhejne SE PEHLE chalta hai.
async function checkRuleLevels(symbol: string, slRaw: unknown, tpRaw: unknown, trailRaw: unknown, entryPx: number | null): Promise<string | null> {
  const given = (v: unknown): boolean => v !== null && v !== undefined && v !== "" && !(typeof v === "number" && Number.isNaN(v));
  for (const [nm, v] of [["SL", slRaw], ["Target", tpRaw], ["Trailing", trailRaw]] as [string, unknown][]) {
    if (given(v) && !posNum(v)) return `${nm} sahi positive number nahi hai`;
  }
  const sl = posNum(slRaw), tp = posNum(tpRaw), trail = posNum(trailRaw);
  if (!sl && !tp && !trail) return null;
  const q = await quoteOf(symbol);
  const bid = q.bid > 0 ? q.bid : q.mark;
  if (!(bid > 0)) return "bid/mark price abhi nahi mila, SL/Target check nahi ho sakta";
  const lo = entryPx && entryPx > 0 ? Math.min(bid, entryPx) : bid;
  const hi = entryPx && entryPx > 0 ? Math.max(bid, entryPx) : bid;
  if (sl && sl >= lo) return `SL ${fmt(sl)} price (${fmt(lo)}) se neeche hona chahiye, warna fill hote hi exit ho jayega`;
  if (tp && tp <= hi) return `Target ${fmt(tp)} price (${fmt(hi)}) se upar hona chahiye`;
  if (sl && tp && sl >= tp) return "SL Target se neeche hona chahiye";
  if (trail) {
    const spread = q.ask > 0 && q.bid > 0 ? q.ask - q.bid : 0;
    if (spread > 0 && trail <= spread) return `Trailing ${fmt(trail)} spread (${fmt(spread)}) se bada rakho, warna turant trigger ho jayega`;
    if (trail >= lo) return `Trailing ${fmt(trail)} price (${fmt(lo)}) se chhota hona chahiye`;
  }
  return null;
}

function createRule(symbol: string, entryQtyRaw: unknown, entryPriceRaw: unknown, slRaw: unknown, tpRaw: unknown, trailRaw: unknown): { ok: boolean; status: number; msg?: string; rule?: Rule } {
  const entryQty = posNum(entryQtyRaw), entryPrice = posNum(entryPriceRaw);
  const sl = posNum(slRaw), tp = posNum(tpRaw), trail = posNum(trailRaw);
  if (!SYMBOL_RE.test(symbol)) return { ok: false, status: 400, msg: "symbol galat" };
  if (!entryQty || !entryPrice) return { ok: false, status: 400, msg: "entry_qty/entry_price sahi nahi" };
  if (!sl && !tp && !trail) return { ok: false, status: 400, msg: "SL / Target / Trailing me se kuch to do" };
  const rule: Rule = {
    id: "r" + Date.now().toString(36) + randomBytes(3).toString("hex"), symbol, side: "LONG",
    entry_qty: entryQty, entry_price: entryPrice, sl, tp, trail, status: "active", created_at: Date.now(),
    high: entryPrice, note: "", error: null, exit_order_id: null, exit_at: 0, exit_tries: 0, trigger_reason: null,
    hi_set: false, hs: 0, breach: 0, nq: 0,
  };
  rules.push(rule);
  persistSoon();
  log(`rule ${rule.id} ${symbol} sl=${sl} tp=${tp} trail=${trail}`);
  return { ok: true, status: 200, rule };
}

function cancelRule(id: string): { ok: boolean; status: number; msg?: string } {
  const r = rules.find((x) => x.id === id);
  if (!r) return { ok: false, status: 404, msg: "rule nahi mila" };
  if (r.status === "active" || r.status === "triggered") { r.status = "cancelled"; r.note = "user ne hataya"; persistSoon(); }
  return { ok: true, status: 200 };
}

// ───────────────────────── /secure — browser se encrypted request (AES-256-GCM) ─────────────────────────
// Browser: key = PBKDF2-SHA256(passphrase, SECURE_SALT, 200k) -> AES-GCM. Envelope: {"n": b64(iv12), "c": b64(ciphertext||tag16)}.
// Andar (plaintext JSON): { id, ts(ms), action, params }.  Response bhi usi key se, naye random iv ke saath, {id, ok, ...}.
// Galat passphrase => decrypt fail => koi kaam nahi. Replay: ts +-30s + id dobara aaye to reject.
const SECURE_PASS = env("SECURE_PASSPHRASE");
const SECURE_SALT = "my-engine-secure-v1";
const SECURE_SKEW_MS = 30_000;
const secureKey: Buffer | null = SECURE_PASS.length >= 12 ? pbkdf2Sync(SECURE_PASS, SECURE_SALT, 200_000, 32, "sha256") : null;
const seenIds = new Map<string, number>();
const authFails = new Map<string, { n: number; reset: number }>();

function clientIp(req: IncomingMessage): string {
  const xf = String(req.headers["x-forwarded-for"] ?? "").split(",")[0].trim();
  return xf || req.socket.remoteAddress || "?";
}
// (2026-10-01) EGRESS BACHAT — gz=true (browser ne plaintext me gz:1 bheja) aur JSON >1KB ho to pehle gzip, phir encrypt
// (encrypt ke baad data compress nahi ho sakta, isliye gzip encrypt se PEHLE). Envelope me z:1 -> browser DecompressionStream.
function secEncrypt(obj: Json, gz = false): { n: string; c: string; z?: 1 } {
  const iv = randomBytes(12);
  const ci = createCipheriv("aes-256-gcm", secureKey as Buffer, iv);
  let data = Buffer.from(JSON.stringify(obj), "utf8");
  let z = false;
  if (gz && data.length > 1024) { data = gzipSync(data); z = true; }
  const ct = Buffer.concat([ci.update(data), ci.final(), ci.getAuthTag()]);
  const out: { n: string; c: string; z?: 1 } = { n: iv.toString("base64"), c: ct.toString("base64") };
  if (z) out.z = 1;
  return out;
}
function secDecrypt(env_: Json): Json {
  const iv = Buffer.from(String(env_?.n ?? ""), "base64");
  const raw = Buffer.from(String(env_?.c ?? ""), "base64");
  if (iv.length !== 12 || raw.length < 17) throw new Error("bad envelope");
  const de = createDecipheriv("aes-256-gcm", secureKey as Buffer, iv);
  de.setAuthTag(raw.subarray(raw.length - 16));
  const pt = Buffer.concat([de.update(raw.subarray(0, raw.length - 16)), de.final()]).toString("utf8");
  return JSON.parse(pt);
}

// Positions/Orders/Balance/History ka cache — min-gap 8s / 60s / 20s, Binance rate-limit safe
type CacheEnt = { ts: number; val: Json; busy: Promise<void> | null };
const cPos: CacheEnt = { ts: 0, val: null, busy: null };
const cHist: CacheEnt = { ts: 0, val: null, busy: null };
const cMeta: CacheEnt = { ts: 0, val: null, busy: null };
async function refreshed(c: CacheEnt, gapMs: number, work: () => Promise<Json>): Promise<Json> {
  if (Date.now() - c.ts >= gapMs) {
    if (!c.busy) c.busy = (async () => { try { c.val = await work(); c.ts = Date.now(); } finally { c.busy = null; } })();
    await c.busy;
  }
  return c.val;
}
const wrap = (r: BResult, t0: number): Json => ({ ok: r.ok, data: r.data, _lat: { render_ms: Date.now() - t0 }, _ts: Date.now() / 1000 });

async function ordersHistoryAll(limit: number): Promise<BResult> {
  const t = await bn("GET", "/eapi/v1/userTrades", { limit: 100 }, true);
  if (!t.ok) return t;
  const syms = new Set<string>();
  for (const x of (Array.isArray(t.data) ? t.data : []) as Json[]) if (x.symbol) syms.add(String(x.symbol));
  const p = await loadPositions();
  for (const s of p.map.keys()) syms.add(s);
  const out: Json[] = [];
  for (const s of [...syms].slice(0, 6)) {
    const h = await bn("GET", "/eapi/v1/historyOrders", { symbol: s, limit }, true);
    if (h.ok && Array.isArray(h.data)) out.push(...(h.data as Json[]));
  }
  out.sort((a, b) => num(b.createTime ?? b.updateTime) - num(a.createTime ?? a.updateTime));
  return { ok: true, status: 200, data: out.slice(0, limit) };
}

// (2026-10-01) EGRESS BACHAT — browser har 3-5s poll karta hai, isliye poori payload har baar nahi bhejte:
//   • params.h  = browser ke paas jo last hash hai. Data (positions/orders/balance + rules) same ho to msgs khaali + unchanged:true.
//   • params.ht = browser ke paas history ka ts. History (fills/ohist/bill/exer — sabse bhaari) sirf tab jaati hai jab server ne
//                 use refresh kiya ho (ts badla) — yaani max 60s me ek baar, har 3s me nahi. Tab pehli baar kholne par browser h/ht
//                 khaali bhejta hai, to full data jaata hai.
// Hash me sirf asli data hai; _ts/_age_s/_lat/ts jaise har-call badalne wale fields hash se bahar.
const hashOf = (v: Json): string => createHash("sha1").update(JSON.stringify(v)).digest("hex").slice(0, 16);
async function secTradeData(tab: string, prm: Json = {}): Promise<Json> {
  const msgs: Json = {};
  const nowS = Date.now() / 1000;
  const prevHash = String(prm?.h ?? "");
  const prevHistTs = Number(prm?.ht ?? 0);
  const aged = (e: Json): Json => (e && e._ts ? { ...e, _age_s: Math.round(Math.max(0, nowS - e._ts) * 100) / 100 } : e);
  let hash = "";
  if (tab === "balance") {
    const m = await refreshed(cMeta, 20_000, async () => {
      const t0 = Date.now();
      const r = await bn("GET", "/eapi/v1/marginAccount", {}, true);
      const meta: Json = { usdt_balance: null, ts: Date.now() / 1000, _lat: { render_ms: Date.now() - t0 }, _ts: Date.now() / 1000, source: "options" };
      if (r.ok && r.data) {
        const a = (r.data.asset || []).find((x: Json) => x.asset === "USDT");
        if (!a) meta.error = `Binance ne USDT entry nahi bheji (asset list: ${JSON.stringify((r.data.asset || []).map((x: Json) => x.asset))}; keys: ${Object.keys(r.data).join(",")}) — Options wallet me USDT transfer kiya hai?`;
        if (a) {
          const f = (k: string): number | null => { const n = Number(a[k]); return Number.isFinite(n) ? n : null; };
          Object.assign(meta, {
            usdt_balance: Number(a.equity ?? 0), usdt_available: Number(a.available ?? 0), usdt_unrealized: Number(a.unrealizedPNL ?? 0),
            margin_balance: f("marginBalance"), initial_margin: f("initialMargin"), maint_margin: f("maintMargin"), adjusted_equity: f("adjustedEquity"),
          });
        }
        const g = (r.data.greek || []).find((x: Json) => x.underlying === UNDERLYING);
        meta.greeks = g ? { delta: g.delta, gamma: g.gamma, theta: g.theta, vega: g.vega } : null;
      } else meta.error = r.data?.msg ?? "account fetch fail";
      return meta;
    });
    const { ts: _t1, _ts: _t2, _lat: _t3, ...stable } = (m ?? {}) as Json;
    hash = hashOf(stable);
    if (hash !== prevHash) msgs.binance_meta = aged(m);
  } else if (tab === "positions" || tab === "orders") {
    const c = await refreshed(cPos, 8_000, async () => {
      const t0 = Date.now();
      const [p, o] = await Promise.all([bn("GET", "/eapi/v1/position", {}, true), bn("GET", "/eapi/v1/openOrders", {}, true)]);
      return { pos: wrap(p, t0), ord: wrap(o, t0), rules: { ok: true, data: rules.map(ruleView) } };
    });
    const rv = rules.map(ruleView);   // rules memory mein hain — hamesha fresh
    hash = hashOf([c.pos?.ok, c.pos?.data, c.ord?.ok, c.ord?.data, rv]);
    if (hash !== prevHash) {
      msgs.trade_positions = aged(c.pos);
      msgs.trade_orders = aged(c.ord);
      msgs.trade_rules = { ok: true, data: rv };
    }
    if (tab === "orders") {
      const h = await refreshed(cHist, 60_000, async () => {
        const t0 = Date.now();
        const [fills, ohist, bill, exer] = await Promise.all([
          bn("GET", "/eapi/v1/userTrades", { limit: 100 }, true),
          ordersHistoryAll(100),
          bn("GET", "/eapi/v1/bill", { currency: "USDT", limit: 100 }, true),
          bn("GET", "/eapi/v1/exerciseRecord", { limit: 100 }, true),
        ]);
        return { fills: { ok: fills.ok, data: fills.data }, ohist: wrap(ohist, t0), bill: { ok: bill.ok, data: bill.data }, exer: { ok: exer.ok, data: exer.data }, ts: Date.now() };
      });
      if (Number(h?.ts ?? 0) !== prevHistTs) {
        msgs.trade_history = { ...h, _lat: h.ohist?._lat, _age_s: Math.round(Math.max(0, nowS - (h.ohist?._ts ?? nowS)) * 100) / 100 };
      }
    }
  }
  return { ok: true, tab, hash, unchanged: hash === prevHash && !Object.keys(msgs).length, msgs, ts: Date.now() };
}


// ── trade_command ──
type IdemEnt = { ts: number; action: string; ack: Json | null; wait: Promise<void>; fin: () => void };
const idemMap = new Map<string, IdemEnt>();
const IDEM_TTL_MS = 3600_000;
const ackMsgOf = (r: BResult): string => `${r.data?.msg ?? "Binance error"}${r.data?.code ? ` (code ${r.data.code})` : ""}`;

async function cmdExec(cmd: Json, cid: string): Promise<{ ok: boolean; msg: string; extra: Json }> {
  const action = String(cmd.action ?? "");
  switch (action) {
    case "place_order": {
      const symbol = String(cmd.symbol ?? "");
      // SL/TP/Trail sirf BUY (entry) par; SELL (close) ke saath bhare fields se rule nahi banta
      const wantRule = String(cmd.side ?? "").toUpperCase() === "BUY" && !!(cmd.sl || cmd.tp || cmd.trail);
      if (wantRule) {
        const bad = await checkRuleLevels(symbol, cmd.sl, cmd.tp, cmd.trail, posNum(cmd.price));
        if (bad) return { ok: false, msg: `${bad} — order NAHI bheja`, extra: {} };
      }
      const c = await placeOrderChecked(symbol, String(cmd.side ?? ""), cmd.quantity, cmd.price, cmd.reduceOnly === true || cmd.reduceOnly === "true", cid);
      if (c.err) return { ok: false, msg: c.err.msg, extra: {} };
      const r = c.r as BResult;
      if (!r.ok) return { ok: false, msg: ackMsgOf(r), extra: { data: r.data } };
      let msg = "order sent";
      if (wantRule) {
        // entry_price = LIMIT order price; rule fail ho to ab msg mein bhi dikhta hai
        const rc = createRule(symbol, cmd.quantity, cmd.price, cmd.sl, cmd.tp, cmd.trail);
        if (!rc.ok) { msg += ` | ⚠️ SL/TP rule SAVE NAHI HUA: ${rc.msg}`; log(`rules_create FAILED (place_order): ${rc.msg}`); }
      }
      return { ok: true, msg, extra: { data: r.data } };
    }
    case "cancel_order": {
      const symbol = String(cmd.symbol ?? ""), oid = String(cmd.orderId ?? "");
      if (!SYMBOL_RE.test(symbol) || !/^\d+$/.test(oid)) return { ok: false, msg: "symbol/orderId galat", extra: {} };
      const r = await bn("DELETE", "/eapi/v1/order", { symbol, orderId: oid }, true);
      log(`cancel ${symbol} #${oid} -> ${r.ok ? "OK" : JSON.stringify(r.data)}`);
      return { ok: r.ok, msg: r.ok ? "cancelled" : ackMsgOf(r), extra: { data: r.data } };
    }
    case "cancel_all": {
      const r = await bn("DELETE", "/eapi/v1/allOpenOrdersByUnderlying", { underlying: UNDERLYING }, true);
      log(`cancel_all -> ${r.ok ? "OK" : JSON.stringify(r.data)}`);
      return { ok: r.ok, msg: r.ok ? "saare open orders cancel" : ackMsgOf(r), extra: { data: r.data } };
    }
    case "panic": {
      const pk = await doPanic();
      const closes: Json[] = pk.closes || [];
      const okN = closes.filter((x) => x && x.ok).length;
      const msg = `cancel-all ${pk.cancel_ok ? "OK" : "FAIL"} | closes ${okN}/${closes.length} | ${(pk.notes || []).join("; ")}`;
      log(`PANIC via /secure -> ${pk.ok ? "OK" : "FAILED"} ${msg}`);
      return { ok: !!pk.ok, msg, extra: { data: pk } };
    }
    case "add_rule": {
      const symbol = String(cmd.symbol ?? "");
      if (!SYMBOL_RE.test(symbol)) return { ok: false, msg: "symbol galat", extra: {} };
      const q = await quoteOf(symbol);
      const entry = q.bid > 0 ? q.bid : q.mark;
      if (!(entry > 0)) return { ok: false, msg: "Current price nahi mila — rule save nahi hua", extra: {} };
      const bad = await checkRuleLevels(symbol, cmd.sl, cmd.tp, cmd.trail, null);
      if (bad) return { ok: false, msg: `${bad} — rule save nahi hua`, extra: {} };
      const rc = createRule(symbol, cmd.quantity, entry, cmd.sl, cmd.tp, cmd.trail);
      return { ok: rc.ok, msg: rc.ok ? "rule saved" : String(rc.msg), extra: rc.ok ? { data: { ok: true, id: rc.rule?.id, rule: rc.rule ? ruleView(rc.rule) : null } } : {} };
    }
    case "remove_rule": {
      const rc = cancelRule(String(cmd.rule_id ?? ""));
      return { ok: rc.ok, msg: rc.ok ? "removed" : String(rc.msg), extra: {} };
    }
    case "close_position": {
      const symbol = String(cmd.symbol ?? "");
      if (!SYMBOL_RE.test(symbol)) return { ok: false, msg: "symbol galat", extra: {} };
      const pos = await loadPositions();
      if (!pos.ok) return { ok: false, msg: `positions nahi mile: ${pos.err.slice(0, 150)}`, extra: {} };
      const p = pos.map.get(symbol);
      if (!p || p.qty <= 0) return { ok: false, msg: "Is symbol ki koi open position nahi mili", extra: {} };
      if (p.short) return { ok: false, msg: "SHORT position hai — yahan se sirf LONG close hota hai, PANIC use karo", extra: {} };
      const notes: string[] = [];
      for (const r of rules) if (r.symbol === symbol && r.status !== "done" && r.status !== "cancelled") { r.status = "cancelled"; r.note = "close_position"; notes.push("rule band"); }
      persistSoon();
      const oo = await bn("GET", "/eapi/v1/openOrders", { symbol }, true);
      if (oo.ok && Array.isArray(oo.data)) {
        for (const o of oo.data as Json[]) {
          if (!o.orderId) continue;
          await bn("DELETE", "/eapi/v1/order", { symbol, orderId: String(o.orderId) }, true);
          notes.push(`order #${o.orderId} cancel`);
        }
      }
      const tick = (await tickOf(symbol)) ?? 5;
      const price = closePrice("SELL", await quoteOf(symbol), tick);
      if (price <= 0) return { ok: false, msg: "bid/mark price nahi mila — close nahi hua (PANIC try karo)", extra: {} };
      const c = await placeOrderChecked(symbol, "SELL", p.qty, price, true, cid);
      if (c.err) return { ok: false, msg: c.err.msg, extra: {} };
      const r = c.r as BResult;
      return {
        ok: r.ok,
        msg: r.ok ? `close order bheja: SELL ${p.qty} @ ${price} (reduceOnly)${notes.length ? " | " + notes.join(", ") : ""}` : ackMsgOf(r),
        extra: { data: r.data, close_qty: p.qty, close_price: price },
      };
    }
    default: return { ok: false, msg: `unknown action: ${action}`, extra: {} };
  }
}

async function secTradeCommand(cmd: Json): Promise<Json> {
  const action = String(cmd?.action ?? "");
  const seq = cmd?.seq ?? null;
  const key = String(cmd?.idem_key ?? "").trim();
  if (!action) return { ok: false, action, seq, msg: "missing action" };
  if (!/^[A-Za-z0-9_-]{8,100}$/.test(key)) return { ok: false, action, seq, msg: "missing/invalid idem_key — order NAHI bheja gaya" };
  const now = Date.now();
  for (const [k, v] of idemMap) if (v.ack && now - v.ts > IDEM_TTL_MS) idemMap.delete(k);
  const ex = idemMap.get(key);
  if (ex) {   // duplicate — execute nahi karte; original khatam hone tak wait, phir wahi ack
    if (ex.action !== action) return { ok: false, action, seq, msg: "idem_key kisi aur action ke saath use ho chuka hai" };
    await Promise.race([ex.wait, sleep(120_000)]);
    log(`trade_command ${action} — DUPLICATE idem_key, cached ack wapas`);
    if (!ex.ack) return { ok: false, action, seq, dup: true, msg: "Ye request abhi bhi chal rahi hai — Orders/Positions tab me check karo" };
    return { ...ex.ack, seq, dup: true };
  }
  let fin: () => void = () => {};
  const ent: IdemEnt = { ts: now, action, ack: null, wait: new Promise<void>((r) => { fin = r; }), fin: () => fin() };
  idemMap.set(key, ent);
  const cid = "hf" + createHash("sha1").update(`${key}|${action}`).digest("hex").slice(0, 20);
  let res: { ok: boolean; msg: string; extra: Json };
  try { res = await cmdExec(cmd, cid); }
  catch (e) { res = { ok: false, msg: errMsg(e), extra: {} }; }
  const ack = { action, seq, ok: !!res.ok, msg: res.msg, ts: Date.now() / 1000, idem_key: key, ...res.extra };
  journalCmd("secure", cmd, ack, cid);
  ent.ack = ack; ent.ts = Date.now(); ent.fin();
  cPos.ts = 0;    // agli poll par positions/orders turant fresh
  cHist.ts = 0;
  log(`trade_command ${action} — ok=${ack.ok} msg=${ack.msg}`);
  return ack;
}

async function secDispatch(action: string, params: Json): Promise<Json> {
  switch (action) {
    case "ping": return { ok: true, pong: Date.now(), trading_enabled: tradingEnabled() };
    case "trade_data": {
      const tab = String(params?.tab ?? "positions");
      if (!["positions", "orders", "balance"].includes(tab)) return { ok: false, msg: "tab galat" };
      return secTradeData(tab, params);
    }
    case "trade_command": return secTradeCommand(params);
    default: return { ok: false, msg: `action '${action}' allowed nahi hai` };
  }
}

async function secureHandler(req: IncomingMessage, res: ServerResponse): Promise<void> {
  if (!secureKey) return send(res, 503, { ok: false, msg: "SECURE_PASSPHRASE set nahi (min 12 chars)" }, true);
  const ip = clientIp(req);
  const now = Date.now();
  const f = authFails.get(ip);
  if (f && f.reset > now && f.n >= 10) { rlogCall(HOP_BR, "auth", "/secure", 0, false, 429, "bahut galat attempts — rate limit"); return send(res, 429, { ok: false, msg: "bahut galat attempts — 1 min baad try karo" }, true); }
  const t0 = Date.now();
  let plain: Json;
  try {
    plain = secDecrypt(await readBody(req));
  } catch {
    const cur = f && f.reset > now ? f : { n: 0, reset: now + 60_000 };
    cur.n++; authFails.set(ip, cur);
    if (cur.n === 5) alertMail("/secure galat attempts", `ip=${ip}: 1 min me ${cur.n} decrypt fail (galat passphrase ya koi guess kar raha hai). 10 par IP block ho jayega.`, "secure-brute");
    log(`SECURE FAIL ip=${ip} n=${cur.n}`);
    rlogCall(HOP_BR, "auth", "/secure", 0, false, 401, "decrypt fail (galat passphrase / kharab body)");
    return send(res, 401, { ok: false, msg: "decrypt fail" }, true);
  }
  const id = String(plain?.id ?? "");
  const ts = Number(plain?.ts);
  if (!/^[\w-]{8,64}$/.test(id) || !Number.isFinite(ts) || Math.abs(now - ts) > SECURE_SKEW_MS) {
    rlogCall(HOP_BR, "auth", "/secure", 0, false, 400, "ts/id galat ya device clock off");
    return send(res, 400, secEncrypt({ id, ok: false, msg: "ts/id galat ya device clock ±30s se zyada off hai" }), true);
  }
  for (const [k, t] of seenIds) if (now - t > 2 * SECURE_SKEW_MS) seenIds.delete(k);
  if (seenIds.has(id)) { rlogCall(HOP_BR, "auth", "/secure", 0, false, 409, "replay — id pehle aa chuka hai"); }
  if (seenIds.has(id)) return send(res, 409, secEncrypt({ id, ok: false, msg: "replay — id pehle aa chuka hai" }), true);
  seenIds.set(id, now);
  let out: Json;
  const actName = String(plain.action ?? "");
  const isCmd = actName === "trade_command";
  try { out = isCmd ? await srcStore.run("order", () => secDispatch(actName, plain.params ?? {})) : await secDispatch(actName, plain.params ?? {}); }
  catch (e) { out = { ok: false, msg: errMsg(e) }; }
  out.render_ms = Date.now() - t0;
  {
    const pa = plain.params ?? {};
    const lsrc = actName === "trade_data" ? `trade_data:${String(pa?.tab ?? "positions")}` : isCmd ? `trade_command:${String(pa?.action ?? "")}` : actName || "?";
    rlogCall(HOP_BR, lsrc, "/secure", out.render_ms, out.ok !== false, out.ok !== false ? 200 : 400, out.msg);
  }
  send(res, 200, secEncrypt({ id, ...out }, plain.gz === 1), true);
}

// ───────────────────────── router ─────────────────────────
async function route(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const url = new URL(req.url ?? "/", "http://localhost");
  const path = url.pathname.replace(/\/+$/, "") || "/";
  const method = (req.method ?? "GET").toUpperCase();

  if (method === "OPTIONS") {
    res.writeHead(204, { "access-control-allow-origin": "*", "access-control-allow-headers": "*", "access-control-allow-methods": "GET,POST,OPTIONS", "access-control-max-age": "600" });
    return void res.end();
  }
  if (path === "/" || path === "/health" || path === "/ping") {
    return send(res, 200, {
      ok: true, service: "my-engine-render", uptime_s: Math.round((Date.now() - stats.started) / 1000),
      commit: env("RENDER_GIT_COMMIT").slice(0, 7), env_missing: envMissing(), engine: { pos_fail_streak: posFailStreak, rules_save_fail_streak: rulesFailStreak },
      trading_enabled: tradingEnabled(), telegram: TG_ON, rules_live: rules.filter((r) => r.status === "active" || r.status === "triggered").length,
      binance: { calls: stats.calls, errors: stats.errors, ban_s: Math.max(0, Math.ceil((banUntil - Date.now()) / 1000)) },
      // Aakhri Binance error (kab, kaunsa endpoint, asli wajah) — bina symbol/qty/price ke, isliye public
      last_error: stats.lastErr ? { ago_s: Math.round((Date.now() - stats.lastErr.at) / 1000), where: stats.lastErr.src, path: stats.lastErr.path, status: stats.lastErr.status, code: stats.lastErr.code, msg: stats.lastErr.msg } : null,
      // Aakhri trade command ka pass/fail (sirf action + ok + message)
      last_trade: (() => { const t = recentCmds[recentCmds.length - 1]; return t ? { ago_s: Math.round((Date.now() - t.at) / 1000), action: t.action, ok: t.ok, msg: t.msg } : null; })(),
      // Poori detail (symbol/side/qty/price ke saath last 10) sirf /health?t=<TRADE_TOKEN> par
      ...(tokenOk(url.searchParams.get("t") ?? "") ? { recent_trades: recentCmds.slice().reverse().map((t) => ({ ...t, ago_s: Math.round((Date.now() - t.at) / 1000), at: undefined })) } : {}),
      store: {
        persistence: STORE_ON ? "hf_data" : "memory+tmp", configured: STORE_ON, rules_synced: rulesSynced,
        last_ok_s_ago: storeState.lastOkAt ? Math.round((Date.now() - storeState.lastOkAt) / 1000) : null,
        last_fail: storeState.lastFailAt ? `${Math.round((Date.now() - storeState.lastFailAt) / 1000)}s ago: ${storeState.lastFail}` : null,
        journal_queue: journalQ.length, log_pending: rlogPending.length,
      },
    }, true);
  }
  if (method === "GET" && (path.startsWith("/api/") || path.startsWith("/eapi/"))) return forwardPublic(url, res);

  if (path === "/secure" && method === "POST") return secureHandler(req, res);

  if (!path.startsWith("/trade/") && !path.startsWith("/rules/")) return send(res, 404, { ok: false, msg: "not found" });
  if (!authOk(req)) {
    log(`AUTH FAIL ${method} ${path} — token ${req.headers["x-trade-token"] ? "mismatch" : "missing"}`);
    return send(res, 401, { ok: false, msg: "bad or missing X-Trade-Token" });
  }

  const body: Json = method === "POST" ? await readBody(req) : {};
  const sym = String(url.searchParams.get("symbol") ?? "");
  const limit = Math.min(100, Math.max(1, Math.floor(num(url.searchParams.get("limit")) || 100)));

  // ── read ──
  if (method === "GET") {
    switch (path) {
      case "/trade/status":
        return send(res, 200, {
          ok: true, tradingEnabled: tradingEnabled(), keysSet: !!(API_KEY && SECRET),
          limits: { maxOrderQty: MAX_ORDER_QTY, maxOrderUsdt: MAX_ORDER_USDT },
          persistence: STORE_ON ? "hf_data" : "memory+tmp", rulesSynced, timeOffsetMs: timeOffset,
        });
      case "/trade/account": return sendB(res, await bn("GET", "/eapi/v1/marginAccount", {}, true));
      case "/trade/positions": return sendB(res, await bn("GET", "/eapi/v1/position", sym ? { symbol: sym } : {}, true));
      case "/trade/orders/open": return sendB(res, await bn("GET", "/eapi/v1/openOrders", sym ? { symbol: sym } : {}, true));
      case "/trade/ticksize": {
        if (!SYMBOL_RE.test(sym)) return send(res, 400, { ok: false, msg: "symbol galat" });
        const t = await tickOf(sym);
        return t ? send(res, 200, { ok: true, symbol: sym, tick: t }) : send(res, 404, { ok: false, msg: "tick nahi mila" });
      }
      case "/trade/fills": return sendB(res, await bn("GET", "/eapi/v1/userTrades", { symbol: sym || undefined, limit }, true));
      case "/trade/orders/history": {
        if (sym) return sendB(res, await bn("GET", "/eapi/v1/historyOrders", { symbol: sym, limit }, true));
        // Binance historyOrders ko symbol chahiye — recent trades + open positions ke symbols se jodte hain
        const t = await bn("GET", "/eapi/v1/userTrades", { limit: 100 }, true);
        if (!t.ok) return sendB(res, t);
        const syms = new Set<string>();
        for (const x of (Array.isArray(t.data) ? t.data : []) as Json[]) if (x.symbol) syms.add(String(x.symbol));
        const p = await loadPositions();
        for (const s of p.map.keys()) syms.add(s);
        const out: Json[] = [];
        for (const s of [...syms].slice(0, 6)) {
          const h = await bn("GET", "/eapi/v1/historyOrders", { symbol: s, limit }, true);
          if (h.ok && Array.isArray(h.data)) out.push(...(h.data as Json[]));
        }
        out.sort((a, b) => num(b.createTime ?? b.updateTime) - num(a.createTime ?? a.updateTime));
        return send(res, 200, out.slice(0, limit));
      }
      case "/trade/bill":
        return sendB(res, await bn("GET", "/eapi/v1/bill", { currency: url.searchParams.get("currency") || "USDT", limit }, true));
      case "/trade/exercise": return sendB(res, await bn("GET", "/eapi/v1/exerciseRecord", { limit }, true));
      case "/rules/list": return send(res, 200, { ok: true, rules: rules.map(ruleView) });
      default: return send(res, 404, { ok: false, msg: "not found" });
    }
  }

  // ── write ──
  if (method === "POST") {
    switch (path) {
      case "/trade/order": {
        const c = await placeOrderChecked(String(body.symbol ?? ""), String(body.side ?? ""), body.quantity, body.price,
          body.reduceOnly === true || body.reduceOnly === "true", body.clientOrderId);
        if (c.err) return send(res, c.err.status, { ok: false, msg: c.err.msg });
        journalCmd("legacy", { action: "place_order", symbol: body.symbol, side: String(body.side ?? "").toUpperCase(), quantity: body.quantity, price: body.price, reduceOnly: body.reduceOnly },
          { ok: (c.r as BResult).ok, msg: (c.r as BResult).ok ? "order sent" : String((c.r as BResult).data?.msg ?? ""), data: (c.r as BResult).data }, typeof body.clientOrderId === "string" ? body.clientOrderId : null);
        return sendB(res, c.r as BResult);
      }
      case "/trade/cancel": {
        const symbol = String(body.symbol ?? "");
        const orderId = String(body.orderId ?? "");
        if (!SYMBOL_RE.test(symbol) || !/^\d+$/.test(orderId)) return send(res, 400, { ok: false, msg: "symbol/orderId galat" });
        const cr = await bn("DELETE", "/eapi/v1/order", { symbol, orderId }, true);
        journalCmd("legacy", { action: "cancel_order", symbol }, { ok: cr.ok, msg: cr.ok ? "cancelled" : String(cr.data?.msg ?? ""), data: cr.data }, null);
        return sendB(res, cr);
      }
      case "/trade/cancel-all": {
        const car = await bn("DELETE", "/eapi/v1/allOpenOrdersByUnderlying", { underlying: UNDERLYING }, true);
        journalCmd("legacy", { action: "cancel_all" }, { ok: car.ok, msg: car.ok ? "saare open orders cancel" : String(car.data?.msg ?? ""), data: car.data }, null);
        return sendB(res, car);
      }
      case "/trade/panic": {
        const pk = await doPanic();
        journalCmd("legacy", { action: "panic" }, { ok: !!pk.ok, msg: (pk.notes || []).join("; "), data: pk }, null);
        return send(res, 200, pk);
      }
      case "/rules/create": {
        const bad = await checkRuleLevels(String(body.symbol ?? ""), body.sl_price, body.target_price, body.trailing_points, posNum(body.entry_price));
        if (bad) return send(res, 400, { ok: false, msg: bad });
        const c = createRule(String(body.symbol ?? ""), body.entry_qty, body.entry_price, body.sl_price, body.target_price, body.trailing_points);
        if (!c.ok) return send(res, c.status, { ok: false, msg: c.msg });
        return send(res, 200, { ok: true, id: (c.rule as Rule).id, rule: ruleView(c.rule as Rule) });
      }
      case "/rules/cancel": {
        const c = cancelRule(String(body.id ?? ""));
        return send(res, c.status, c.ok ? { ok: true } : { ok: false, msg: c.msg });
      }
      default: return send(res, 404, { ok: false, msg: "not found" });
    }
  }
  return send(res, 405, { ok: false, msg: "method not allowed" });
}

// ───────────────────────── boot ─────────────────────────
const server = createServer((req, res) => {
  route(req, res).catch((e: unknown) => {
    log("route error:", errMsg(e));
    if (!res.headersSent) send(res, 500, { ok: false, msg: errMsg(e) });
    else res.end();
  });
});

async function main(): Promise<void> {
  if (!TRADE_TOKEN) log("WARNING: TRADE_TOKEN set nahi — /trade/* aur /rules/* sab 401 denge");
  if (!API_KEY || !SECRET) log("WARNING: OPT_API_KEY / OPT_SECRET_KEY set nahi");
  { const miss = envMissing(); if (miss.length) alertMail("Render env missing", `Ye env vars set nahi hain: ${miss.join(", ")}\nIn ke bina trading/rules/secure kaam adhoora rahega.`, "env-missing"); }
  await loadRules();
  { const liveBoot = rules.filter((r) => r.status === "active" || r.status === "triggered").length;
    if (liveBoot) alertMail("Render restart hua", `${liveBoot} live rule wapas load hue. Restart ke dauran SL/Target nahi chale the — positions check karo.`, "boot-live"); }
  // Render free tier 15 min bina inbound traffic ke so jata hai — apne public URL ko har 4 min ping (best-effort; paid plan sabse safe)
  { const selfUrl = (env("RENDER_EXTERNAL_URL") || env("SELF_URL")).replace(/\/+$/, "");
    if (selfUrl) setInterval(() => { fetch(`${selfUrl}/ping`, { signal: AbortSignal.timeout(10000) }).catch(() => { /* ignore */ }); }, 4 * 60_000).unref(); }
  await syncTime();
  setInterval(() => { void syncTime(); }, 5 * 60_000);
  setInterval(() => { void engineTick(); }, 5000);
  setInterval(() => { void rlogFlush("periodic"); }, RLOG_FLUSH_MS);
  rlogRow("event", "render", "boot", null, true, `Render start | trading_enabled=${tradingEnabled()} | rules=${rules.length}`, { rules: rules.length });
  server.listen(PORT, "0.0.0.0", () => log(`my-engine server up on :${PORT} | trading_enabled=${tradingEnabled()} | rules=${rules.length}`));
}
let shuttingDown = false;
for (const sig of ["SIGTERM", "SIGINT"] as const) {
  process.on(sig, () => {
    if (shuttingDown) return;
    shuttingDown = true;
    rlogRow("event", "render", "shutdown", null, true, `Render band ho raha hai (${sig}) | uptime ${Math.round((Date.now() - stats.started) / 1000)}s`);
    void Promise.race([Promise.all([rlogFlush("shutdown"), journalFlush(), saveRulesNow(true)]), sleep(4000)]).finally(() => process.exit(0));
  });
}
process.on("unhandledRejection", (e) => { log("unhandledRejection:", errMsg(e)); try { alertMail("Render unhandledRejection", errMsg(e).slice(0, 400), "unhandled-rej"); } catch { /* ignore */ } });
process.on("uncaughtException", (e) => { log("uncaughtException:", errMsg(e)); try { alertMail("Render uncaughtException", errMsg(e).slice(0, 400), "uncaught-exc"); } catch { /* ignore */ } });
void main();
