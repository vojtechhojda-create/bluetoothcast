import { createPublicKey, randomBytes, verify } from "node:crypto";
import { EventEmitter } from "node:events";
import { createServer } from "node:https";
import { join } from "node:path";
import { WebSocket, WebSocketServer, type RawData } from "ws";
import { audiohub, channels, rate } from "./audio.js";
import { tls } from "./cert.js";
import { config, root, save } from "./config.js";
import { receiver, sendfile } from "./files.js";
import { check, pcname } from "./pairing.js";
import { frame_file_in, port_phone } from "./protocol.js";
import { askyesno, mediakey, openurl, setclipboard } from "./winsys.js";

export const events = new EventEmitter();

interface session {
  id: string;
  name: string;
  rx: receiver;
  audiofn: ((f: Buffer) => void) | null;
}

const sessions = new Map<WebSocket, session>();
const audio = new audiohub(process.env.PROXYLINK_LOOPBACK ?? join(root, "bin", "loopback.exe"));
let fileid = 1;

const reply = (ws: WebSocket, o: object) => {
  if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(o));
};

const tobuf = (raw: RawData): Buffer =>
  Array.isArray(raw) ? Buffer.concat(raw) : Buffer.isBuffer(raw) ? raw : Buffer.from(raw);

const clean = (s: unknown, max: number) =>
  String(s ?? "")
    .replace(/[\u0000-\u001f]/g, " ")
    .trim()
    .slice(0, max);

export const devicelist = () => [...sessions.values()].map((s) => ({ id: s.id, name: s.name }));

export function startphoneserver(onerror: (e: NodeJS.ErrnoException) => void) {
  const server = createServer({ key: tls.key, cert: tls.cert, minVersion: "TLSv1.2" });
  const wss = new WebSocketServer({ server, maxPayload: 4 * 1024 * 1024 });
  wss.on("connection", (ws, req) => onconnection(ws, req.socket.remoteAddress ?? "?"));
  wss.on("error", () => {});
  server.on("error", onerror);
  server.listen(port_phone, "0.0.0.0");
  return server;
}

function validpubkey(b64: unknown): string | null {
  if (typeof b64 !== "string" || b64.length > 400) return null;
  try {
    const k = createPublicKey({ key: Buffer.from(b64, "base64"), format: "der", type: "spki" });
    return k.asymmetricKeyType === "ec" ? b64 : null;
  } catch {
    return null;
  }
}

function onconnection(ws: WebSocket, ip: string) {
  let pending = false;
  let challenge: { id: string; nonce: Buffer } | null = null;
  const timer = setTimeout(() => {
    if (!sessions.has(ws) && !pending) ws.close(4401, "auth timeout");
  }, 10_000);

  const accept = (id: string, name: string) => {
    config.devices[id] = { ...config.devices[id], name, lastseen: Date.now() };
    save();
    sessions.set(ws, {
      id,
      name,
      audiofn: null,
      rx: new receiver(config.downloaddir, (n, path) => {
          events.emit("info", `received ${n} from ${name}`);
          events.emit("received", path);
        }),
    });
    reply(ws, { type: "auth.pair.ok", name: pcname });
    events.emit("change");
    events.emit("info", `${name} connected`);
  };

  ws.on("message", async (raw, isbinary) => {
    const s = sessions.get(ws);
    if (s) return route(ws, s, raw, isbinary);
    if (pending) return;
    if (isbinary) return ws.close(4401, "unauthenticated");

    let m: any;
    try {
      m = JSON.parse(raw.toString());
    } catch {
      return reply(ws, { type: "error", code: "bad_json" });
    }

    if (m?.type === "auth.hello") {
      const id = clean(m.deviceid, 64);
      if (!config.devices[id]?.pubkey) {
        reply(ws, { type: "auth.pair.error", code: "unknown_device" });
        return ws.close(4403, "unknown device");
      }
      challenge = { id, nonce: randomBytes(32) };
      return reply(ws, { type: "auth.challenge", nonce: challenge.nonce.toString("base64") });
    }

    if (m?.type === "auth.resume") {
      const c = challenge;
      challenge = null;
      const d = c ? config.devices[c.id] : undefined;
      let ok = false;
      try {
        ok =
          !!c &&
          !!d?.pubkey &&
          typeof m.sig === "string" &&
          verify(
            "sha256",
            c.nonce,
            { key: Buffer.from(d.pubkey, "base64"), format: "der", type: "spki" },
            Buffer.from(m.sig, "base64"),
          );
      } catch {
        ok = false;
      }
      if (!ok || !c || !d) {
        reply(ws, { type: "auth.pair.error", code: "invalid_signature" });
        return ws.close(4403, "invalid signature");
      }
      clearTimeout(timer);
      return accept(c.id, d.name);
    }

    if (m?.type !== "auth.pair") {
      reply(ws, { type: "error", code: "unauthenticated" });
      return ws.close(4401, "unauthenticated");
    }

    const id = clean(m.deviceid, 64);
    const name = clean(m.devicename, 64) || "unknown";
    const pubkey = validpubkey(m.pubkey);
    if (!id || !check(m.token)) {
      reply(ws, { type: "auth.pair.error", code: "invalid_token" });
      return ws.close(4403, "invalid token");
    }
    if (!pubkey) {
      reply(ws, { type: "auth.pair.error", code: "missing_pubkey" });
      return ws.close(4403, "missing pubkey");
    }

    pending = true;
    clearTimeout(timer);
    let allowed = id in config.devices;
    if (!allowed) {
      reply(ws, { type: "auth.pair.pending" });
      allowed = await askyesno("proxylink", `allow "${name}" (${ip}) to connect to this pc?`).catch(() => false);
    }
    if (!allowed || ws.readyState !== WebSocket.OPEN) {
      reply(ws, { type: "auth.pair.denied", code: "denied" });
      return ws.close(4403, "denied");
    }

    config.devices[id] = { name, lastseen: Date.now(), pubkey };
    accept(id, name);
  });

  ws.on("close", () => {
    clearTimeout(timer);
    const s = sessions.get(ws);
    if (!s) return;
    if (s.audiofn) audio.remove(s.audiofn);
    s.rx.abort();
    sessions.delete(ws);
    events.emit("change");
  });

  ws.on("error", () => ws.terminate());
}

function route(ws: WebSocket, s: session, raw: RawData, isbinary: boolean) {
  if (isbinary) {
    const b = tobuf(raw);
    if (b[0] === frame_file_in) s.rx.chunk(b);
    return;
  }
  let m: any;
  try {
    m = JSON.parse(raw.toString());
  } catch {
    return reply(ws, { type: "error", code: "bad_json" });
  }

  switch (m?.type) {
    case "ping":
      return reply(ws, { type: "pong", t: m.t });

    case "audio.start": {
      if (s.audiofn) return;
      const fn = (f: Buffer) => {
        if (ws.readyState === WebSocket.OPEN && ws.bufferedAmount < 256 * 1024) ws.send(f);
      };
      reply(ws, { type: "audio.config", rate, channels });
      if (audio.add(fn)) s.audiofn = fn;
      else reply(ws, { type: "error", code: "audio_unavailable" });
      return;
    }

    case "audio.stop":
      if (s.audiofn) audio.remove(s.audiofn);
      s.audiofn = null;
      return;

    case "media.control":
      if (!mediakey(m.action)) reply(ws, { type: "error", code: "unknown_action" });
      return;

    case "share.send": {
      const data = typeof m.data === "string" ? m.data.slice(0, 200_000) : "";
      if (!data) return;
      if (m.kind === "link" && config.openlinks && openurl(data)) {
        events.emit("info", `opened link from ${s.name}`);
      } else {
        setclipboard(data);
        events.emit("info", `${m.kind === "link" ? "link" : "text"} from ${s.name} copied`);
      }
      return;
    }

    case "file.offer":
      return s.rx.offer(m.id, m.name);

    case "file.done":
      return s.rx.done(m.id);

    default:
      return reply(ws, { type: "error", code: "unknown_type" });
  }
}

export function pushto(kind: "link" | "text", data: string, title: string, target?: string): number {
  const msg = JSON.stringify({ type: "share.push", kind, data, title });
  let n = 0;
  for (const [ws, s] of sessions) {
    if (target && s.id !== target) continue;
    if (ws.readyState !== WebSocket.OPEN) continue;
    ws.send(msg);
    n++;
  }
  return n;
}

export async function sendfiles(paths: string[]): Promise<number> {
  let n = 0;
  for (const p of paths) {
    for (const ws of sessions.keys()) {
      if (ws.readyState !== WebSocket.OPEN) continue;
      if (await sendfile(ws, p, fileid++).catch(() => false)) n++;
    }
  }
  return n;
}

export function forgetall() {
  config.devices = {};
  save();
  for (const ws of sessions.keys()) ws.close(4403, "forgotten");
}

export function disconnectall() {
  for (const ws of sessions.keys()) ws.close(4408, "disconnected by pc");
}

export function closeall() {
  audio.halt();
  for (const ws of sessions.keys()) ws.close(1001, "shutdown");
}
