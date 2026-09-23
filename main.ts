// binance-proxy.ts
// Deno Deploy par chalne wala reverse proxy — Binance REST API ko forward karta hai.
// Deploy karne ke baad iska URL kuch aisa milega: https://<project-name>.deno.dev

const UPSTREAM_MAP: Record<string, string> = {
  "/api/": "https://api.binance.com/api/",
  "/eapi/": "https://eapi.binance.com/eapi/",
};

// ── WebSocket relay map — teeno streams jo app.py use karta hai ───────────
const WS_MAP: Record<string, string> = {
  "/ws/mark":  "wss://fstream.binance.com/market/stream?streams=btcusdt@optionMarkPrice",
  "/ws/trade": "wss://fstream.binance.com/public/stream?streams=btcusdt@optionTrade",
  "/ws/spot":  "wss://stream.binance.com:9443/ws/btcusdt@aggTrade",
};

Deno.serve({ port: Number(Deno.env.get("PORT") ?? 8000) }, async (req: Request) => {
  const url = new URL(req.url);

  // ── WebSocket relay ───────────────────────────────────────────────────
  if (req.headers.get("upgrade")?.toLowerCase() === "websocket") {
    const targetWsUrl = WS_MAP[url.pathname];
    if (!targetWsUrl) {
      return new Response("Unknown WS path — /ws/mark, /ws/trade, /ws/spot use karo", { status: 404 });
    }

    const { socket: clientSocket, response } = Deno.upgradeWebSocket(req);
    const upstreamSocket = new WebSocket(targetWsUrl);

    // Binance se aaya message → client (app.py) ko forward
    upstreamSocket.onmessage = (e) => {
      if (clientSocket.readyState === WebSocket.OPEN) clientSocket.send(e.data);
    };
    upstreamSocket.onclose = () => {
      if (clientSocket.readyState === WebSocket.OPEN) clientSocket.close();
    };
    upstreamSocket.onerror = () => {
      if (clientSocket.readyState === WebSocket.OPEN) clientSocket.close();
    };

    // Client se aaya message (agar koi ho, ping/pong waghera) → Binance ko forward
    clientSocket.onmessage = (e) => {
      if (upstreamSocket.readyState === WebSocket.OPEN) upstreamSocket.send(e.data);
    };
    clientSocket.onclose = () => {
      if (upstreamSocket.readyState === WebSocket.OPEN) upstreamSocket.close();
    };

    return response;
  }

  // Health-check — browser me URL khol ke check kar sakte ho ki proxy zinda hai
  if (url.pathname === "/") {
    return new Response("Binance proxy is running ✅", { status: 200 });
  }

  // Sahi upstream (spot ya options) chuno path prefix ke hisaab se
  const prefix = Object.keys(UPSTREAM_MAP).find((p) => url.pathname.startsWith(p));
  if (!prefix) {
    return new Response("Not found — path /api/... ya /eapi/... use karo", { status: 404 });
  }

  const upstreamBase = UPSTREAM_MAP[prefix];
  const upstreamPath = url.pathname.slice(prefix.length);
  const upstreamUrl = upstreamBase + upstreamPath + url.search;

  try {
    const upstreamResp = await fetch(upstreamUrl, {
      method: req.method,
      headers: {
        // sirf zaroori headers forward karo — host header nahi bhejna
        "X-MBX-APIKEY": req.headers.get("X-MBX-APIKEY") ?? "",
        "Content-Type": req.headers.get("Content-Type") ?? "application/json",
      },
      body: req.method === "GET" || req.method === "HEAD" ? undefined : await req.text(),
    });

    const respBody = await upstreamResp.text();
    return new Response(respBody, {
      status: upstreamResp.status,
      headers: {
        "Content-Type": upstreamResp.headers.get("Content-Type") ?? "application/json",
        "Access-Control-Allow-Origin": "*",
      },
    });
  } catch (err) {
    return new Response(JSON.stringify({ error: `Proxy fetch failed: ${err}` }), {
      status: 502,
      headers: { "Content-Type": "application/json" },
    });
  }
});
