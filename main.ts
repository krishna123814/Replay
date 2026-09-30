// main.ts — My-engine Render server (Binance OPTIONS trade + rules engine + public relay)
//
// Runtime: Node 22+ (ya Bun / Deno 2). Sirf built-in modules + global fetch/WebSocket —
// koi npm dependency nahi. TypeScript-only runtime features (enum etc.) use nahi kiye,
// isliye `node --experimental-strip-types main.ts`, `tsx main.ts`, `bun main.ts` sab chalte hain.
//
// ENV: PORT, OPT_API_KEY, OPT_SECRET_KEY, TRADE_TOKEN, TRADING_ENABLED (true/false),
//      BREVO_API_KEY, ALERT_EMAIL_TO, [BREVO_SENDER_EMAIL], [MAX_ORDER_QTY], [MAX_ORDER_USDT],
//      TEST_SUPABASE_URL + TEST_SUPABASE_KEY (rules restart-proof rakhne ke liye, optional)
//      SECURE_PASSPHRASE (browser se encrypted POST /secure ke liye; min 12 chars)
//
// Contract (app.py se nikala hua):
//   Auth: header X-Trade-Token == TRADE_TOKEN  (sirf /trade/* aur /rules/* par)
//   GET  /trade/status /trade/account /trade/positions /trade/orders/open[?symbol]
//        /trade/ticksize?symbol /trade/fills /trade/orders/history /trade/bill /trade/exercise
//   POST /trade/order /trade/cancel /trade/cancel-all /trade/panic
//   POST /rules/create  GET /rules/list  POST /rules/cancel
//   POST /secure  (browser se AES-256-GCM encrypted; actions: ping, trade_data, trade_command[place_order,cancel_order,cancel_all,panic,close_position,add_rule,remove_rule])
//   GET  /health /ping /  (no auth, keep-alive)   GET /snapshot (legacy option-chain snapshot)
//   GET  /api/v3/* , /eapi/v1/*  -> SIRF public whitelist (signed/private calls yahan se nahi jaate)

import { createServer } from "node:http";
import type { IncomingMessage, ServerResponse } from "node:http";
import { createCipheriv, createDecipheriv, createHash, createHmac, pbkdf2Sync, randomBytes, timingSafeEqual } from "node:crypto";
import { appendFileSync, readFileSync, writeFileSync } from "node:fs";

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
const SB_URL = (env("TEST_SUPABASE_URL") || env("SUPABASE_URL")).replace(/\/+$/, "");
const SB_KEY =
  env("TEST_SUPABASE_KEY") || env("TEST_SUPABASE_SERVICE_KEY") ||
  env("TEST_SUPABASE_ANON_KEY") || env("SUPABASE_KEY");

const EAPI = "https://eapi.binance.com";
const SPOT_HOSTS = ["https://api.binance.com", "https://data-api.binance.vision"];
const UNDERLYING = "BTCUSDT";
const SYMBOL_RE = /^BTC-\d{6}-\d+-[CP]$/;

const tradingEnabled = (): boolean => ["true", "1", "yes", "on"].includes(env("TRADING_ENABLED").toLowerCase());
const log = (...a: unknown[]): void => console.log(new Date().toISOString(), ...a);
const errMsg = (e: unknown): string => (e instanceof Error ? e.message : String(e));
const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));

// ───────────────────────── Binance client ─────────────────────────
let timeOffset = 0;
let banUntil = 0;
const stats = { calls: 0, errors: 0, started: Date.now(), lastCallAt: 0 };

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
  if (Date.now() < banUntil) {
    return { ok: false, status: 429, data: { code: -1003, msg: `Binance IP-ban active (~${Math.ceil((banUntil - Date.now()) / 1000)}s baaki) — request bheji nahi` } };
  }
  if (signed && (!API_KEY || !SECRET)) {
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
  try {
    const r = await fetch(`${EAPI}${path}${query ? "?" + query : ""}`, { method, headers, signal: AbortSignal.timeout(15000) });
    const data = parseBinance(await r.text());
    if (!r.ok) {
      stats.errors++;
      log(`BINANCE ERR ${method} ${path} -> HTTP ${r.status} ${JSON.stringify(data).slice(0, 250)}`);
      const m = /banned until (\d+)/.exec(String(data?.msg ?? ""));
      if (m) banUntil = Number(m[1]);
      else if (r.status === 418 || r.status === 429) banUntil = Date.now() + 60_000;
    }
    return { ok: r.ok, status: r.status, data };
  } catch (e) {
    stats.errors++;
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
function closePrice(side: "SELL" | "BUY", q: Quote, tick: number): number {
  if (side === "SELL") {
    const base = q.bid > 0 ? q.bid * 0.97 : q.mark > 0 ? q.mark * 0.7 : 0;
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
function alertMail(subject: string, body: string, key = subject): void {
  log("ALERT:", subject, "|", body);
  if (!BREVO_KEY || !ALERT_TO) return;
  const now = Date.now();
  if (now - (lastAlert.get(key) ?? 0) < 30_000) return;
  lastAlert.set(key, now);
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

// ───────────────────────── rules store (memory + Supabase + /tmp) ─────────────────────────
type RuleStatus = "active" | "triggered" | "done" | "error" | "cancelled";
type Rule = {
  id: string; symbol: string; side: "LONG"; entry_qty: number; entry_price: number;
  sl: number | null; tp: number | null; trail: number | null;
  status: RuleStatus; created_at: number; high: number; note: string; error: string | null;
  exit_order_id: string | null; exit_at: number; exit_tries: number; trigger_reason: string | null;
};
let rules: Rule[] = [];
const LOCAL_FILE = "/tmp/opt_rules.json";
let saveTimer: ReturnType<typeof setTimeout> | null = null;

const sbHeaders = (): Record<string, string> => ({ apikey: SB_KEY, authorization: `Bearer ${SB_KEY}`, "content-type": "application/json" });

async function saveRulesNow(): Promise<void> {
  const keep = rules.filter((r) => r.status === "active" || r.status === "triggered").concat(
    rules.filter((r) => r.status !== "active" && r.status !== "triggered").slice(-50));
  rules = keep;
  try { writeFileSync(LOCAL_FILE, JSON.stringify(rules)); } catch { /* ephemeral disk */ }
  if (!SB_URL || !SB_KEY) return;
  try {
    const r = await fetch(`${SB_URL}/rest/v1/trade_rules?on_conflict=id`, {
      method: "POST", headers: { ...sbHeaders(), Prefer: "resolution=merge-duplicates" },
      body: JSON.stringify({ id: "rules", data: rules }), signal: AbortSignal.timeout(10000),
    });
    if (!r.ok) log("supabase save fail:", r.status, (await r.text()).slice(0, 200));
  } catch (e) { log("supabase save error:", errMsg(e)); }
}
function persistSoon(): void {
  if (saveTimer) return;
  saveTimer = setTimeout(() => { saveTimer = null; void saveRulesNow(); }, 1000);
}
async function loadRules(): Promise<void> {
  if (SB_URL && SB_KEY) {
    try {
      const r = await fetch(`${SB_URL}/rest/v1/trade_rules?id=eq.rules&select=data`, { headers: sbHeaders(), signal: AbortSignal.timeout(10000) });
      if (r.ok) {
        const j = (await r.json()) as Json;
        if (Array.isArray(j) && j[0] && Array.isArray(j[0].data)) { rules = j[0].data as Rule[]; log("rules Supabase se load:", rules.length); return; }
      } else log("supabase load fail:", r.status);
    } catch (e) { log("supabase load error:", errMsg(e)); }
  }
  try { rules = JSON.parse(readFileSync(LOCAL_FILE, "utf8")) as Rule[]; log("rules local file se load:", rules.length); } catch { rules = []; }
}

// ───────────────────────── trade journal (Supabase `trade_journal` + /tmp fallback) ─────────────────────────
// Har order/cancel/cancel-all/panic/close + rules-engine exit ki ek row. Kabhi throw nahi karta, order ko block nahi karta.
// Supabase fail ho to memory queue mein rehta hai (max 500) aur har 30s retry hota hai; /tmp/opt_journal.jsonl hamesha likhi jaati hai.
const JOURNAL_FILE = "/tmp/opt_journal.jsonl";
const journalQ: Json[] = [];
let journalBusy = false;
async function journalFlush(): Promise<void> {
  if (journalBusy || !journalQ.length || !SB_URL || !SB_KEY) return;
  journalBusy = true;
  const batch = journalQ.slice(0, 100);
  try {
    const r = await fetch(`${SB_URL}/rest/v1/trade_journal`, {
      method: "POST", headers: { ...sbHeaders(), Prefer: "return=minimal" }, body: JSON.stringify(batch), signal: AbortSignal.timeout(10000),
    });
    if (r.ok) journalQ.splice(0, batch.length);
    else log("journal supabase fail:", r.status, (await r.text()).slice(0, 200));
  } catch (e) { log("journal supabase error:", errMsg(e)); }
  finally { journalBusy = false; }
}
function journal(entry: Json): void {
  try {
    const row = { ts: new Date().toISOString(), ...entry };
    try { appendFileSync(JOURNAL_FILE, JSON.stringify(row) + "\n"); } catch { /* ephemeral disk */ }
    if (SB_URL && SB_KEY) {
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
const EXIT_MAX_TRIES = 6;
let engineBusy = false;

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
  const tick = (await tickOf(r.symbol)) ?? 5;
  const q = await quoteOf(r.symbol);
  const price = closePrice("SELL", q, tick);
  if (price <= 0) { r.error = "bid/mark price nahi mila — exit retry hoga"; return; }
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
  }
  log(`[rule ${r.id}] ${r.note || r.error}`);
}

async function handleRule(r: Rule, pos: Map<string, Pos>, exiting: Set<string>): Promise<void> {
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
    if (Date.now() - r.exit_at >= EXIT_RETRY_MS) {
      if (r.exit_tries >= EXIT_MAX_TRIES) {
        r.status = "error"; r.error = r.error || "exit 6 baar try kiya, position abhi bhi open — MANUAL close karo";
        alertMail(`Rule ERROR ${r.symbol}`, `${r.error}\nPosition abhi bhi open hai!`, `err-${r.id}`);
        return;
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

  const q = await quoteOf(r.symbol);
  if (q.bid <= 0) return;
  if (r.trail) r.high = Math.max(r.high, q.bid);
  const levels: number[] = [];
  if (r.sl) levels.push(r.sl);
  if (r.trail) levels.push(r.high - r.trail);
  const stop = levels.length ? Math.max(...levels) : null;
  let reason: string | null = null;
  if (stop !== null && q.bid <= stop) reason = `SL/Trailing hit — bid ${fmt(q.bid)} <= ${fmt(stop)}`;
  else if (r.tp && q.bid >= r.tp) reason = `Target hit — bid ${fmt(q.bid)} >= ${fmt(r.tp)}`;
  if (!reason) return;

  r.status = "triggered"; r.trigger_reason = `${r.symbol}: ${reason}`; r.exit_tries = 0; r.exit_at = 0;
  exiting.add(r.symbol);
  alertMail(`Rule TRIGGERED ${r.symbol}`, r.trigger_reason, `trg-${r.id}`);
  await sendExit(r, p.qty);
}

async function engineTick(): Promise<void> {
  if (engineBusy) return;
  const live = rules.filter((r) => r.status === "active" || r.status === "triggered");
  if (!live.length) return;
  engineBusy = true;
  try {
    const pos = await loadPositions();
    if (!pos.ok) { log("engine: positions nahi mili, is tick skip:", pos.err); return; }
    const exiting = new Set(live.filter((r) => r.status === "triggered").map((r) => r.symbol));
    for (const r of live) {
      try { await handleRule(r, pos.map, exiting); }
      catch (e) {
        r.status = "error"; r.error = errMsg(e);
        alertMail(`Rule ERROR ${r.symbol}`, r.error, `err-${r.id}`);
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

// ───────────────────────── option mark WebSocket (/snapshot ke liye) ─────────────────────────
type MarkRow = { mark: number; bid: number; ask: number; iv: number; delta: number; gamma: number; theta: number; vega: number; ts: number };
const marks = new Map<string, MarkRow>();
let spot: { price: number; ts: number } | null = null;
let wsLive = false;
let wsLastMsg = 0;
let wsRef: Json = null;

function startMarkWs(): void {
  const WS = (globalThis as Json).WebSocket;
  if (!WS) { log("global WebSocket nahi hai (Node 22+ chahiye) — /snapshot khaali rahega"); return; }
  const connect = (): void => {
    try {
      const ws = new WS("wss://fstream.binance.com/market/ws/btcusdt@optionMarkPrice");
      wsRef = ws;
      ws.onopen = () => { wsLive = true; log("mark WS connected"); };
      ws.onmessage = (ev: Json) => {
        wsLastMsg = Date.now();
        let j: Json;
        try { j = JSON.parse(String(ev.data)); } catch { return; }
        for (const m of (Array.isArray(j) ? j : [j]) as Json[]) {
          if (!m || !m.s) continue;
          marks.set(String(m.s), {
            mark: num(m.mp), bid: num(m.bo), ask: num(m.ao), iv: num(m.vo ?? ((num(m.b) + num(m.a)) / 2)),
            delta: num(m.d), gamma: num(m.g), theta: num(m.t), vega: num(m.v), ts: Date.now(),
          });
          if (num(m.i) > 0) spot = { price: num(m.i), ts: Date.now() };
        }
      };
      const retry = (): void => { wsLive = false; setTimeout(connect, 3000); };
      ws.onclose = retry;
      ws.onerror = () => { try { ws.close(); } catch { /* ignore */ } };
    } catch (e) { log("mark WS start fail:", errMsg(e)); setTimeout(connect, 5000); }
  };
  connect();
  setInterval(() => {   // half-open socket watchdog
    if (wsLive && Date.now() - wsLastMsg > 60_000) { try { wsRef?.close(); } catch { /* ignore */ } }
  }, 15_000);
}

function buildSnapshot(nStrikes: number, nExpiries: number): Json {
  const now = Date.now();
  const parsed: { sym: string; exp: string; strike: number }[] = [];
  for (const sym of marks.keys()) {
    const m = /^BTC-(\d{6})-(\d+)-[CP]$/.exec(sym);
    if (m) parsed.push({ sym, exp: m[1], strike: Number(m[2]) });
  }
  let exps = [...new Set(parsed.map((p) => p.exp))].sort();
  if (nExpiries > 0) exps = exps.slice(0, nExpiries);
  const allowed = new Set<string>();
  for (const e of exps) {
    const strikes = [...new Set(parsed.filter((p) => p.exp === e).map((p) => p.strike))].sort((a, b) => a - b);
    let pick = strikes;
    if (nStrikes > 0 && spot && strikes.length) {
      let idx = 0;
      strikes.forEach((s, i) => { if (Math.abs(s - spot!.price) < Math.abs(strikes[idx] - spot!.price)) idx = i; });
      pick = strikes.slice(Math.max(0, idx - nStrikes), idx + nStrikes + 1);
    }
    for (const p of parsed) if (p.exp === e && pick.includes(p.strike)) allowed.add(p.sym);
  }
  const rows: Json[] = [];
  for (const sym of allowed) {
    const m = marks.get(sym)!;
    rows.push([sym, m.mark, m.bid, m.ask, m.iv, m.delta, m.gamma, m.theta, m.vega, now - m.ts]);
  }
  return {
    keys: ["mark", "bid", "ask", "iv", "delta", "gamma", "theta", "vega", "mark_age_ms"], rows,
    feeds: {
      mark: { connected: wsLive, age_ms: wsLastMsg ? now - wsLastMsg : null },
      trade: { connected: false, age_ms: null },
      spot: { connected: wsLive && !!spot },
    },
    spot: spot?.price ?? null, spot_age_ms: spot ? now - spot.ts : null,
  };
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
function authOk(req: IncomingMessage): boolean {
  if (!TRADE_TOKEN) return false;
  const got = String(req.headers["x-trade-token"] ?? "");
  const a = createHash("sha256").update(got).digest();
  const b = createHash("sha256").update(TRADE_TOKEN).digest();
  return timingSafeEqual(a, b);
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
      try {
        stats.calls++;
        const r = await fetch(`${host}${path}${url.search}`, { signal: AbortSignal.timeout(15000) });
        const body = await r.text();
        if (r.ok) { res.writeHead(200, { "content-type": "application/json", "access-control-allow-origin": "*" }); return void res.end(body); }
        last = { status: r.status, body };
      } catch (e) { last = { status: 504, body: JSON.stringify({ msg: errMsg(e) }) }; }
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
  if (qty > MAX_ORDER_QTY) return { err: { status: 403, msg: `qty ${qty} > MAX_ORDER_QTY ${MAX_ORDER_QTY}` } };
  if (qty * price > MAX_ORDER_USDT) return { err: { status: 403, msg: `order value ${(qty * price).toFixed(2)} > MAX_ORDER_USDT ${MAX_ORDER_USDT}` } };
  const cid = typeof cidRaw === "string" && /^[\w-]{1,36}$/.test(cidRaw) ? cidRaw : undefined;
  const r = await placeLimit(symbol, side, qty, price, reduceOnly, cid);
  log(`order ${side} ${symbol} qty=${qty} px=${price} ro=${reduceOnly} -> ${r.ok ? "OK" : JSON.stringify(r.data)}`);
  return { r };
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
function secEncrypt(obj: Json): { n: string; c: string } {
  const iv = randomBytes(12);
  const ci = createCipheriv("aes-256-gcm", secureKey as Buffer, iv);
  const ct = Buffer.concat([ci.update(JSON.stringify(obj), "utf8"), ci.final(), ci.getAuthTag()]);
  return { n: iv.toString("base64"), c: ct.toString("base64") };
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

// Positions/Orders/Balance/History ka cache — HF wale min-gap jaisa (8s / 60s / 20s), Binance rate-limit safe
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

async function secTradeData(tab: string): Promise<Json> {
  const msgs: Json = {};
  const nowS = Date.now() / 1000;
  const aged = (e: Json): Json => (e && e._ts ? { ...e, _age_s: Math.round(Math.max(0, nowS - e._ts) * 100) / 100 } : e);
  if (tab === "balance") {
    const m = await refreshed(cMeta, 20_000, async () => {
      const t0 = Date.now();
      const r = await bn("GET", "/eapi/v1/account", {}, true);
      const meta: Json = { usdt_balance: null, ts: Date.now() / 1000, _lat: { render_ms: Date.now() - t0 }, _ts: Date.now() / 1000, source: "options" };
      if (r.ok && r.data) {
        const a = (r.data.asset || []).find((x: Json) => x.asset === "USDT");
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
    msgs.binance_meta = aged(m);
  } else if (tab === "positions" || tab === "orders") {
    const c = await refreshed(cPos, 8_000, async () => {
      const t0 = Date.now();
      const [p, o] = await Promise.all([bn("GET", "/eapi/v1/position", {}, true), bn("GET", "/eapi/v1/openOrders", {}, true)]);
      return { pos: wrap(p, t0), ord: wrap(o, t0), rules: { ok: true, data: rules.map(ruleView) } };
    });
    msgs.trade_positions = aged(c.pos);
    msgs.trade_orders = aged(c.ord);
    msgs.trade_rules = { ok: true, data: rules.map(ruleView) };   // rules memory mein hain — hamesha fresh
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
      msgs.trade_history = { ...h, _lat: h.ohist?._lat, _age_s: Math.round(Math.max(0, nowS - (h.ohist?._ts ?? nowS)) * 100) / 100 };
    }
  }
  return { ok: true, tab, msgs, ts: Date.now() };
}


// ── trade_command (HF ka _trade_command_exec + trade_command_run ka port) ──
type IdemEnt = { ts: number; action: string; ack: Json | null; wait: Promise<void>; fin: () => void };
const idemMap = new Map<string, IdemEnt>();
const IDEM_TTL_MS = 3600_000;
const ackMsgOf = (r: BResult): string => `${r.data?.msg ?? "Binance error"}${r.data?.code ? ` (code ${r.data.code})` : ""}`;

async function cmdExec(cmd: Json, cid: string): Promise<{ ok: boolean; msg: string; extra: Json }> {
  const action = String(cmd.action ?? "");
  switch (action) {
    case "place_order": {
      const symbol = String(cmd.symbol ?? "");
      const c = await placeOrderChecked(symbol, String(cmd.side ?? ""), cmd.quantity, cmd.price, cmd.reduceOnly === true || cmd.reduceOnly === "true", cid);
      if (c.err) return { ok: false, msg: c.err.msg, extra: {} };
      const r = c.r as BResult;
      if (!r.ok) return { ok: false, msg: ackMsgOf(r), extra: { data: r.data } };
      let msg = "order sent";
      if (cmd.sl || cmd.tp || cmd.trail) {
        // entry_price = LIMIT order price (HF jaisa hi); rule fail ho to ab msg mein bhi dikhta hai
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
      return secTradeData(tab);
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
  if (f && f.reset > now && f.n >= 10) return send(res, 429, { ok: false, msg: "bahut galat attempts — 1 min baad try karo" }, true);
  const t0 = Date.now();
  let plain: Json;
  try {
    plain = secDecrypt(await readBody(req));
  } catch {
    const cur = f && f.reset > now ? f : { n: 0, reset: now + 60_000 };
    cur.n++; authFails.set(ip, cur);
    log(`SECURE FAIL ip=${ip} n=${cur.n}`);
    return send(res, 401, { ok: false, msg: "decrypt fail" }, true);
  }
  const id = String(plain?.id ?? "");
  const ts = Number(plain?.ts);
  if (!/^[\w-]{8,64}$/.test(id) || !Number.isFinite(ts) || Math.abs(now - ts) > SECURE_SKEW_MS) {
    return send(res, 400, secEncrypt({ id, ok: false, msg: "ts/id galat ya device clock ±30s se zyada off hai" }), true);
  }
  for (const [k, t] of seenIds) if (now - t > 2 * SECURE_SKEW_MS) seenIds.delete(k);
  if (seenIds.has(id)) return send(res, 409, secEncrypt({ id, ok: false, msg: "replay — id pehle aa chuka hai" }), true);
  seenIds.set(id, now);
  let out: Json;
  try { out = await secDispatch(String(plain.action ?? ""), plain.params ?? {}); }
  catch (e) { out = { ok: false, msg: errMsg(e) }; }
  out.render_ms = Date.now() - t0;
  send(res, 200, secEncrypt({ id, ...out }), true);
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
      trading_enabled: tradingEnabled(), rules_live: rules.filter((r) => r.status === "active" || r.status === "triggered").length,
      binance: { calls: stats.calls, errors: stats.errors, ban_s: Math.max(0, Math.ceil((banUntil - Date.now()) / 1000)) },
      mark_ws: { connected: wsLive, symbols: marks.size },
    }, true);
  }
  if (path === "/snapshot" && method === "GET") {
    return send(res, 200, buildSnapshot(num(url.searchParams.get("strikes")), num(url.searchParams.get("expiries"))), true);
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
          persistence: SB_URL && SB_KEY ? "supabase" : "memory+tmp", timeOffsetMs: timeOffset,
        });
      case "/trade/account": return sendB(res, await bn("GET", "/eapi/v1/account", {}, true));
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
  await loadRules();
  await syncTime();
  setInterval(() => { void syncTime(); }, 5 * 60_000);
  setInterval(() => { void engineTick(); }, 5000);
  startMarkWs();
  server.listen(PORT, "0.0.0.0", () => log(`my-engine server up on :${PORT} | trading_enabled=${tradingEnabled()} | rules=${rules.length}`));
}
process.on("unhandledRejection", (e) => log("unhandledRejection:", errMsg(e)));
process.on("uncaughtException", (e) => log("uncaughtException:", errMsg(e)));
void main();
