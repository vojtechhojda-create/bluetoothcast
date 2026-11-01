import { spawn, type ChildProcess } from "node:child_process";
import { createPublicKey, randomBytes, verify } from "node:crypto";
import { EventEmitter } from "node:events";
import { existsSync } from "node:fs";
import { createServer } from "node:https";
import { join } from "node:path";
import { WebSocket, WebSocketServer, type RawData } from "ws";
import { audiohub, channels, rate } from "./audio.js";
import { chanbus, chanre } from "./chan.js";
import { tls } from "./cert.js";
import { config, root, save } from "./config.js";
import { receiver, sendfile } from "./files.js";
import { check, pcname } from "./pairing.js";
import { frame_audio_up, frame_file_in, frame_file_out, port_phone } from "./protocol.js";
import { askyesno, mediakey, openurl, setclipboard } from "./winsys.js";

export const events = new EventEmitter();

interface up {
  proc: ChildProcess;
  source: string;
  started: number;
}

interface session {
  id: string;
  name: string;
  ws: WebSocket;
  rx: receiver;
  audiostop: (() => void) | null;
  down: { pid: number | null } | null;
  downgain: number;
  up: up | null;
  upgain: number;
}

const sessions = new Map<WebSocket, session>();
const exe = process.env.PROXYLINK_LOOPBACK ?? join(root, "bin", "loopback.exe");
const audio = new audiohub(exe);
let fileid = 1;

export const infolog: { t: number; text: string }[] = [];
events.on("info", (text: string) => {
  infolog.push({ t: Date.now(), text });
  if (infolog.length > 30) infolog.shift();
});

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

const clampgain = (g: unknown) => (typeof g === "number" && isFinite(g) ? Math.min(2, Math.max(0, g)) : 1);

function scale(buf: Buffer, gain: number, offset: number): Buffer {
  const out = Buffer.from(buf);
  for (let i = offset; i + 1 < out.length; i += 2) {
    out.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(out.readInt16LE(i) * gain))), i);
  }
  return out;
}

export const devicelist = () => [...sessions.values()].map((s) => ({ id: s.id, name: s.name }));

const byid = (id: unknown) => [...sessions.values()].find((s) => s.id === id);

function startdown(s: session, pid: number | null) {
  s.audiostop?.();
  s.audiostop = null;
  reply(s.ws, { type: "audio.config", rate, channels });
  const stop = audio.add(
    pid,
    (f) => {
      if (s.ws.readyState === WebSocket.OPEN && s.ws.bufferedAmount < 256 * 1024) {
        s.ws.send(s.downgain === 1 ? f : scale(f, s.downgain, 1));
      }
    },
    () => {
      s.audiostop = null;
      s.down = null;
      reply(s.ws, { type: "error", code: "audio_failed" });
      events.emit("change");
    },
  );
  if (stop) {
    s.audiostop = stop;
    s.down = { pid };
  } else {
    s.down = null;
    reply(s.ws, { type: "error", code: "audio_unavailable" });
  }
  events.emit("change");
}

function stopdown(s: session, notify: boolean) {
  s.audiostop?.();
  s.audiostop = null;
  s.down = null;
  if (notify) reply(s.ws, { type: "audio.stopped" });
  events.emit("change");
}

function startup(s: session, rate_: unknown, channels_: unknown, source: unknown) {
  stopup(s, false);
  const r = Number.isInteger(rate_) && (rate_ as number) >= 8000 && (rate_ as number) <= 96000 ? (rate_ as number) : 48000;
  const c = channels_ === 1 ? 1 : 2;
  if (!existsSync(exe)) return reply(s.ws, { type: "error", code: "playback_unavailable" });
  const proc = spawn(exe, ["--play", String(r), String(c)], { stdio: ["pipe", "ignore", "ignore"], windowsHide: true });
  proc.stdin?.on("error", () => {});
  const u: up = { proc, source: clean(source, 16) || "phone", started: Date.now() };
  const end = () => {
    if (s.up !== u) return;
    s.up = null;
    if (Date.now() - u.started < 2000) reply(s.ws, { type: "error", code: "playback_failed" });
    reply(s.ws, { type: "audio.up.stopped" });
    events.emit("change");
  };
  proc.on("exit", end);
  proc.on("error", end);
  s.up = u;
  events.emit("change");
}

function stopup(s: session, notify: boolean) {
  const u = s.up;
  if (!u) return;
  s.up = null;
  u.proc.kill();
  if (notify) reply(s.ws, { type: "audio.up.stopped" });
  events.emit("change");
}

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
      ws,
      audiostop: null,
      down: null,
      downgain: 1,
      up: null,
      upgain: 1,
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
    s.audiostop?.();
    stopup(s, false);
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
    else if (b[0] === frame_audio_up) {
      const stdin = s.up?.proc.stdin;
      if (!stdin || stdin.writableLength > 256 * 1024) return;
      const pcm = b.subarray(1);
      stdin.write(s.upgain === 1 ? pcm : scale(pcm, s.upgain, 0));
    }
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

    case "audio.start":
      return startdown(s, Number.isInteger(m.pid) && m.pid > 0 ? (m.pid as number) : null);

    case "audio.stop":
      return stopdown(s, false);

    case "audio.up.start":
      return startup(s, m.rate, m.channels, m.source);

    case "audio.up.stop":
      return stopup(s, false);

    case "apps.list":
      void audio.list().then((apps) => reply(ws, { type: "apps.list", apps }));
      return;

    case "chan.msg": {
      if (typeof m.ch !== "string" || !chanre.test(m.ch)) return;
      if (m.ch === "echo") return reply(ws, { type: "chan.msg", ch: "echo", data: m.data });
      chanbus.emit("from-phone", { ch: m.ch, from: s.id, name: s.name, data: m.data });
      return;
    }

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

export function sendchan(ch: string, data: unknown, to?: string): number {
  const msg = JSON.stringify({ type: "chan.msg", ch, data });
  if (msg.length > 1_000_000) return 0;
  let n = 0;
  for (const [ws, s] of sessions) {
    if (to && s.id !== to) continue;
    if (ws.readyState !== WebSocket.OPEN) continue;
    ws.send(msg);
    n++;
  }
  return n;
}

export const api = {
  snapshot() {
    return {
      pc: pcname,
      hasaudio: audio.available(),
      settings: { openlinks: config.openlinks, openfiles: config.openfiles },
      phones: [...sessions.values()].map((s) => ({
        id: s.id,
        name: s.name,
        down: s.down,
        downgain: s.downgain,
        up: s.up ? { source: s.up.source } : null,
        upgain: s.upgain,
      })),
      log: infolog.slice(-12),
    };
  },
  listapps: () => audio.list(),
  startdown(id: unknown, pid: unknown) {
    const s = byid(id);
    if (s) startdown(s, Number.isInteger(pid) && (pid as number) > 0 ? (pid as number) : null);
  },
  stopdown(id: unknown) {
    const s = byid(id);
    if (s) stopdown(s, true);
  },
  setdowngain(id: unknown, g: unknown) {
    const s = byid(id);
    if (s) s.downgain = clampgain(g);
  },
  stopup(id: unknown) {
    const s = byid(id);
    if (s) stopup(s, true);
  },
  setupgain(id: unknown, g: unknown) {
    const s = byid(id);
    if (s) s.upgain = clampgain(g);
  },
  disconnect(id: unknown) {
    byid(id)?.ws.close(4408, "disconnected by pc");
  },
  setsettings(o: { openlinks?: unknown; openfiles?: unknown }) {
    if (typeof o.openlinks === "boolean") config.openlinks = o.openlinks;
    if (typeof o.openfiles === "boolean") config.openfiles = o.openfiles;
    save();
    events.emit("change");
  },
  beginupload(id: unknown, name: unknown, size: unknown) {
    const s = byid(id);
    if (!s || s.ws.readyState !== WebSocket.OPEN) return null;
    const fid = fileid++;
    s.ws.send(
      JSON.stringify({ type: "file.offer", id: fid, name: clean(name, 120) || "file", size: Number(size) || -1 }),
    );
    return { ws: s.ws, fid };
  },
  uploadchunk(u: { ws: WebSocket; fid: number }, data: Buffer, done: () => void) {
    if (u.ws.readyState !== WebSocket.OPEN) return done();
    const out = Buffer.allocUnsafe(5 + data.length);
    out[0] = frame_file_out;
    out.writeInt32BE(u.fid, 1);
    data.copy(out, 5);
    u.ws.send(out, () => done());
  },
  enduploads(u: { ws: WebSocket; fid: number }) {
    reply(u.ws, { type: "file.done", id: u.fid });
  },
};

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
  for (const s of sessions.values()) stopup(s, false);
  for (const ws of sessions.keys()) ws.close(1001, "shutdown");
}
