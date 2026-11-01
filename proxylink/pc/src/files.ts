import { createReadStream, createWriteStream, existsSync, mkdirSync, type WriteStream } from "node:fs";
import { stat } from "node:fs/promises";
import { basename, join, parse } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { WebSocket } from "ws";
import { frame_file_out } from "./protocol.js";

const reserved = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])$/i;

export function safename(raw: unknown): string {
  let n = basename(String(raw ?? "").replace(/\\/g, "/"))
    .replace(/[\u0000-\u001f<>:"|?*]/g, "_")
    .replace(/[. ]+$/, "")
    .slice(0, 120);
  if (!n || n === "." || n === "..") n = "file";
  if (reserved.test(parse(n).name)) n = "_" + n;
  return n;
}

function uniquepath(dir: string, name: string): string {
  const { name: stem, ext } = parse(name);
  let p = join(dir, name);
  let i = 1;
  while (existsSync(p)) p = join(dir, `${stem} (${i++})${ext}`);
  return p;
}

interface tx {
  out: WriteStream;
  path: string;
}

export class receiver {
  private active = new Map<number, tx>();

  constructor(
    private dir: string,
    private onsaved: (name: string, path: string) => void,
  ) {
    mkdirSync(dir, { recursive: true });
  }

  offer(id: unknown, name: unknown) {
    if (!Number.isInteger(id) || this.active.has(id as number) || this.active.size >= 8) return;
    const path = uniquepath(this.dir, safename(name));
    const out = createWriteStream(path, { flags: "wx" });
    out.on("error", () => this.active.delete(id as number));
    this.active.set(id as number, { out, path });
  }

  chunk(buf: Buffer) {
    if (buf.length < 6) return;
    this.active.get(buf.readInt32BE(1))?.out.write(buf.subarray(5));
  }

  done(id: unknown) {
    const t = this.active.get(id as number);
    if (!t) return;
    this.active.delete(id as number);
    t.out.end(() => this.onsaved(basename(t.path), t.path));
  }

  abort() {
    for (const t of this.active.values()) t.out.destroy();
    this.active.clear();
  }
}

export async function sendfile(ws: WebSocket, path: string, id: number): Promise<boolean> {
  const st = await stat(path);
  if (!st.isFile()) return false;
  ws.send(JSON.stringify({ type: "file.offer", id, name: basename(path), size: st.size }));
  for await (const part of createReadStream(path, { highWaterMark: 32 * 1024 })) {
    const chunk = part as Buffer;
    while (ws.bufferedAmount > 4_000_000) {
      if (ws.readyState !== WebSocket.OPEN) return false;
      await sleep(5);
    }
    if (ws.readyState !== WebSocket.OPEN) return false;
    const out = Buffer.allocUnsafe(5 + chunk.length);
    out[0] = frame_file_out;
    out.writeInt32BE(id, 1);
    chunk.copy(out, 5);
    ws.send(out);
  }
  ws.send(JSON.stringify({ type: "file.done", id }));
  return true;
}
