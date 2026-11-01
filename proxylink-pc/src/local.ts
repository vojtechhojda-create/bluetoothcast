import { createServer } from "node:http";
import QRCode from "qrcode";
import { WebSocketServer, type WebSocket } from "ws";
import { fresh, payload, pcname } from "./pairing.js";
import { port_local } from "./protocol.js";
import { devicelist, pushto } from "./server.js";

const hosts = new Set([`127.0.0.1:${port_local}`, `localhost:${port_local}`]);
const hostok = (h?: string) => !!h && hosts.has(h);
const originok = (o?: string) => !!o && (o.startsWith("moz-extension://") || o.startsWith("chrome-extension://"));

const page = (svg: string) => `<!doctype html>
<meta charset="utf-8">
<title>pair phone</title>
<style>
body{margin:0;min-height:100vh;display:grid;place-items:center;background:#111827;color:#e5e7eb;font:16px system-ui,sans-serif}
main{text-align:center}
.qr{background:#fff;padding:12px;border-radius:16px;width:320px;margin:0 auto 20px}
.qr svg{display:block;width:100%;height:auto}
p{margin:6px 0;color:#9ca3af}
</style>
<main>
<h2>pair phone</h2>
<div class="qr">${svg}</div>
<p>open proxylink on your phone and scan this code</p>
<p>valid for 10 minutes, single use</p>
</main>`;

function onext(ws: WebSocket) {
  const reply = (o: object) => ws.readyState === ws.OPEN && ws.send(JSON.stringify(o));
  ws.on("message", (raw) => {
    let m: any;
    try {
      m = JSON.parse(raw.toString());
    } catch {
      return;
    }
    if (m?.type === "status") return reply({ type: "status", pc: pcname, devices: devicelist() });
    if (m?.type !== "send") return;

    let kind: "link" | "text" = m.kind === "link" ? "link" : "text";
    const data = typeof m.data === "string" ? m.data.slice(0, 100_000) : "";
    const title = typeof m.title === "string" ? m.title.slice(0, 200) : "";
    const target = typeof m.device === "string" ? m.device : undefined;
    if (!data) return reply({ type: "result", ok: false, error: "empty" });
    if (kind === "link") {
      try {
        const u = new URL(data);
        if (u.protocol !== "http:" && u.protocol !== "https:") kind = "text";
      } catch {
        kind = "text";
      }
    }
    const count = pushto(kind, data, title, target);
    reply({ type: "result", ok: count > 0, count, error: count ? undefined : "no phone connected" });
  });
  ws.on("error", () => ws.terminate());
}

export function startlocal(onerror: (e: NodeJS.ErrnoException) => void) {
  const wss = new WebSocketServer({ noServer: true, maxPayload: 256 * 1024 });
  wss.on("connection", onext);

  const server = createServer(async (req, res) => {
    if (!hostok(req.headers.host)) return void res.writeHead(403).end();
    if (req.method === "GET" && req.url === "/pair") {
      fresh();
      const svg = await QRCode.toString(payload(), { type: "svg", margin: 1 });
      res.writeHead(200, {
        "content-type": "text/html; charset=utf-8",
        "cache-control": "no-store",
        "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'",
      });
      return void res.end(page(svg));
    }
    res.writeHead(404).end();
  });

  server.on("upgrade", (req, socket, head) => {
    if (!hostok(req.headers.host) || req.url !== "/ext" || !originok(req.headers.origin)) {
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit("connection", ws, req));
  });

  wss.on("error", () => {});
  server.on("error", onerror);
  server.listen(port_local, "127.0.0.1");
  return server;
}
