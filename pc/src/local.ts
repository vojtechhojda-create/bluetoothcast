import { timingSafeEqual } from "node:crypto";
import { randomBytes } from "node:crypto";
import { readFile } from "node:fs/promises";
import { createServer } from "node:http";
import { join } from "node:path";
import QRCode from "qrcode";
import { WebSocketServer, type WebSocket } from "ws";
import { chanbus, chanre, type chanmsg } from "./chan.js";
import { devtoken, root } from "./config.js";
import { fresh, payload, pcname } from "./pairing.js";
import { port_local } from "./protocol.js";
import { api, devicelist, events, pushto, sendchan } from "./server.js";

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

const tickets = new Map<string, number>();
const sessionsok = new Set<string>();

export function newticket(): string {
  const t = randomBytes(16).toString("hex");
  tickets.set(t, Date.now() + 60_000);
  for (const [k, v] of tickets) if (v < Date.now()) tickets.delete(k);
  return t;
}

const panelorigin = (o?: string) => o === `http://127.0.0.1:${port_local}` || o === `http://localhost:${port_local}`;

const statics: Record<string, [string, string]> = {
  "/panel": ["index.html", "text/html; charset=utf-8"],
  "/panel.js": ["panel.js", "text/javascript; charset=utf-8"],
  "/panel.css": ["panel.css", "text/css; charset=utf-8"],
  "/logo.svg": ["logo.svg", "image/svg+xml"],
};

const panels = new Set<WebSocket>();
let pushtimer: NodeJS.Timeout | undefined;
const pushall = () => {
  clearTimeout(pushtimer);
  pushtimer = setTimeout(() => {
    const msg = JSON.stringify({ type: "state", ...api.snapshot() });
    for (const p of panels) if ((p as any).authed && p.readyState === p.OPEN) p.send(msg);
  }, 80);
};
events.on("change", pushall);
events.on("info", pushall);

function onpanel(ws: WebSocket) {
  const reply = (o: object) => ws.readyState === ws.OPEN && ws.send(JSON.stringify(o));
  let upload: { ws: WebSocket; fid: number } | null = null;
  const timer = setTimeout(() => !(ws as any).authed && ws.close(4401, "auth timeout"), 5000);
  panels.add(ws);
  ws.on("close", () => {
    clearTimeout(timer);
    panels.delete(ws);
  });
  ws.on("error", () => ws.terminate());

  ws.on("message", async (raw, isbinary) => {
    if (!(ws as any).authed) {
      let h: any;
      try {
        h = JSON.parse(raw.toString());
      } catch {
        return void ws.close(4403);
      }
      let ok = false;
      if (h?.type === "hello" && typeof h.ticket === "string") {
        const exp = tickets.get(h.ticket);
        tickets.delete(h.ticket);
        ok = !!exp && exp > Date.now();
      } else if (h?.type === "hello" && typeof h.session === "string") {
        ok = sessionsok.has(h.session);
      }
      if (!ok) return void ws.close(4403, "bad ticket");
      const session = typeof h.session === "string" ? h.session : randomBytes(16).toString("hex");
      sessionsok.add(session);
      (ws as any).authed = true;
      clearTimeout(timer);
      reply({ type: "hello.ok", session });
      reply({ type: "state", ...api.snapshot() });
      return;
    }

    if (isbinary) {
      const data = Buffer.isBuffer(raw) ? raw : Array.isArray(raw) ? Buffer.concat(raw) : Buffer.from(raw);
      if (upload) api.uploadchunk(upload, data, () => reply({ type: "file.ack" }));
      return;
    }

    let m: any;
    try {
      m = JSON.parse(raw.toString());
    } catch {
      return;
    }
    switch (m?.type) {
      case "state":
        return void reply({ type: "state", ...api.snapshot() });
      case "apps":
        return void api.listapps().then((apps) => reply({ type: "apps", apps }));
      case "down.start":
        return api.startdown(m.device, m.pid);
      case "down.stop":
        return api.stopdown(m.device);
      case "down.gain":
        return api.setdowngain(m.device, m.gain);
      case "up.stop":
        return api.stopup(m.device);
      case "up.gain":
        return api.setupgain(m.device, m.gain);
      case "disconnect":
        return api.disconnect(m.device);
      case "settings":
        return api.setsettings(m);
      case "send.text": {
        const data = typeof m.data === "string" ? m.data.slice(0, 100_000) : "";
        if (!data) return;
        let kind: "link" | "text" = m.kind === "link" ? "link" : "text";
        try {
          const u = new URL(data);
          kind = u.protocol === "http:" || u.protocol === "https:" ? "link" : "text";
        } catch {
          kind = "text";
        }
        const n = pushto(kind, data, "", typeof m.device === "string" && m.device ? m.device : undefined);
        return void reply({ type: "sent", ok: n > 0, count: n });
      }
      case "file.begin":
        upload = api.beginupload(m.device, m.name, m.size);
        return void reply({ type: "file.begin", ok: !!upload });
      case "file.end":
        if (upload) api.enduploads(upload);
        upload = null;
        return void reply({ type: "file.end" });
    }
  });
}

function ondev(ws: WebSocket) {
  let authed = false;
  const subs = new Set<string>();
  const reply = (o: object) => ws.readyState === ws.OPEN && ws.send(JSON.stringify(o));
  const timer = setTimeout(() => !authed && ws.close(4401, "auth timeout"), 5000);
  const fwd = (e: chanmsg) => {
    if (authed && (subs.has(e.ch) || subs.has("*"))) reply({ type: "chan.msg", ...e });
  };
  chanbus.on("from-phone", fwd);
  ws.on("close", () => {
    clearTimeout(timer);
    chanbus.off("from-phone", fwd);
  });
  ws.on("error", () => ws.terminate());
  ws.on("message", (raw) => {
    let m: any;
    try {
      m = JSON.parse(raw.toString());
    } catch {
      return;
    }
    if (!authed) {
      const a = Buffer.from(String(m?.token ?? ""));
      const b = Buffer.from(devtoken);
      if (m?.type !== "hello" || a.length !== b.length || !timingSafeEqual(a, b)) return void ws.close(4403, "bad token");
      authed = true;
      clearTimeout(timer);
      for (const c of Array.isArray(m.channels) ? m.channels : []) {
        if (typeof c === "string" && (c === "*" || chanre.test(c))) subs.add(c);
      }
      return void reply({ type: "hello.ok", pc: pcname, devices: devicelist() });
    }
    if (m?.type === "devices") return void reply({ type: "devices", devices: devicelist() });
    if (m?.type === "chan.send" && typeof m.ch === "string" && chanre.test(m.ch)) {
      const n = sendchan(m.ch, m.data, typeof m.to === "string" ? m.to : undefined);
      reply({ type: "chan.result", ok: n > 0, count: n });
    }
  });
}

export function startlocal(onerror: (e: NodeJS.ErrnoException) => void) {
  const wss = new WebSocketServer({ noServer: true, maxPayload: 256 * 1024 });
  wss.on("connection", onext);
  const panelwss = new WebSocketServer({ noServer: true, maxPayload: 1024 * 1024 });
  panelwss.on("connection", onpanel);
  panelwss.on("error", () => {});
  const devwss = new WebSocketServer({ noServer: true, maxPayload: 1024 * 1024 });
  devwss.on("connection", ondev);
  devwss.on("error", () => {});

  const server = createServer(async (req, res) => {
    if (!hostok(req.headers.host)) return void res.writeHead(403).end();
    const st = req.method === "GET" && req.url ? statics[req.url.split("?")[0]] : undefined;
    if (st) {
      try {
        const body = await readFile(join(root, "panel", st[0]));
        res.writeHead(200, {
          "content-type": st[1],
          "cache-control": "no-store",
          "content-security-policy":
            "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src ws://127.0.0.1:8788 ws://localhost:8788",
        });
        return void res.end(body);
      } catch {
        return void res.writeHead(404).end();
      }
    }
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
    if (req.url === "/panel-ws") {
      if (!hostok(req.headers.host) || !panelorigin(req.headers.origin)) return void socket.destroy();
      panelwss.handleUpgrade(req, socket, head, (ws) => panelwss.emit("connection", ws, req));
      return;
    }
    if (req.url === "/dev") {
      const o = req.headers.origin;
      if (!hostok(req.headers.host) || (o && /^https?:/i.test(o))) return void socket.destroy();
      devwss.handleUpgrade(req, socket, head, (ws) => devwss.emit("connection", ws, req));
      return;
    }
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
