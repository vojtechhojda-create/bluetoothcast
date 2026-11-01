import { execFile, spawn, type ChildProcess } from "node:child_process";
import { existsSync } from "node:fs";
import { frame_audio } from "./protocol.js";

export const rate = 48000;
export const channels = 2;

const framebytes = 4;
const maxchunk = 7680;

export interface appinfo {
  pid: number;
  name: string;
  title: string;
  active: boolean;
}

interface sub {
  send(f: Buffer): void;
  fail(): void;
  dead: boolean;
}

interface source {
  proc: ChildProcess;
  subs: Set<sub>;
  carry: Buffer;
  started: number;
}

const text = (v: unknown, max: number) =>
  String(v ?? "")
    .replace(/[\u0000-\u001f]/g, " ")
    .trim()
    .slice(0, max);

export class audiohub {
  private sources = new Map<string, source>();

  constructor(private exe: string) {}

  available() {
    return existsSync(this.exe);
  }

  add(pid: number | null, send: (f: Buffer) => void, fail: () => void): (() => void) | null {
    if (!this.available()) return null;
    const key = pid ? String(pid) : "all";
    const me: sub = { send, fail, dead: false };
    const s = this.sources.get(key) ?? this.start(key, pid, new Set());
    s.subs.add(me);
    return () => {
      me.dead = true;
      const cur = this.sources.get(key);
      if (!cur) return;
      cur.subs.delete(me);
      if (!cur.subs.size) {
        this.sources.delete(key);
        cur.proc.kill();
      }
    };
  }

  halt() {
    for (const s of this.sources.values()) s.proc.kill();
    this.sources.clear();
  }

  list(): Promise<appinfo[]> {
    return new Promise((res) => {
      if (!this.available()) return res([]);
      execFile(this.exe, ["--list"], { timeout: 5000, windowsHide: true, encoding: "utf8" }, (e, out) => {
        if (e) return res([]);
        try {
          const a = JSON.parse(out);
          if (!Array.isArray(a)) return res([]);
          res(
            a
              .filter((x) => Number.isInteger(x?.pid) && x.pid > 0)
              .slice(0, 50)
              .map((x) => ({ pid: x.pid, name: text(x.name, 48), title: text(x.title, 80), active: !!x.active })),
          );
        } catch {
          res([]);
        }
      });
    });
  }

  private start(key: string, pid: number | null, subs: Set<sub>): source {
    const proc = spawn(this.exe, pid ? ["--pid", String(pid)] : [], {
      stdio: ["ignore", "pipe", "ignore"],
      windowsHide: true,
    });
    const s: source = { proc, subs, carry: Buffer.alloc(0), started: Date.now() };
    this.sources.set(key, s);
    proc.stdout?.on("data", (d: Buffer) => this.feed(s, d));
    let ended = false;
    const end = () => {
      if (ended) return;
      ended = true;
      if (this.sources.get(key) !== s) return;
      this.sources.delete(key);
      if (Date.now() - s.started < 3000) {
        for (const x of s.subs) x.fail();
        return;
      }
      setTimeout(() => {
        for (const x of s.subs) if (x.dead) s.subs.delete(x);
        if (s.subs.size && !this.sources.has(key)) this.start(key, pid, s.subs);
      }, 1000);
    };
    proc.on("exit", end);
    proc.on("error", end);
    return s;
  }

  private feed(s: source, d: Buffer) {
    const b = s.carry.length ? Buffer.concat([s.carry, d]) : d;
    const usable = b.length - (b.length % framebytes);
    s.carry = Buffer.from(b.subarray(usable));
    for (let o = 0; o < usable; o += maxchunk) {
      const end = Math.min(o + maxchunk, usable);
      const out = Buffer.allocUnsafe(end - o + 1);
      out[0] = frame_audio;
      b.copy(out, 1, o, end);
      for (const x of s.subs) x.send(out);
    }
  }
}
