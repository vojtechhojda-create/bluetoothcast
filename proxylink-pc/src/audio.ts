import { spawn, type ChildProcess } from "node:child_process";
import { existsSync } from "node:fs";
import { frame_audio } from "./protocol.js";

export const rate = 48000;
export const channels = 2;

const framebytes = 4;
const maxchunk = 7680;

export class audiohub {
  private proc: ChildProcess | null = null;
  private subs = new Set<(f: Buffer) => void>();
  private carry = Buffer.alloc(0);

  constructor(private exe: string) {}

  available() {
    return existsSync(this.exe);
  }

  add(fn: (f: Buffer) => void): boolean {
    if (!this.available()) return false;
    this.subs.add(fn);
    this.ensure();
    return true;
  }

  remove(fn: (f: Buffer) => void) {
    this.subs.delete(fn);
    if (!this.subs.size) this.halt();
  }

  halt() {
    const p = this.proc;
    this.proc = null;
    this.carry = Buffer.alloc(0);
    p?.kill();
  }

  private ensure() {
    if (this.proc) return;
    const p = spawn(this.exe, [], { stdio: ["ignore", "pipe", "ignore"], windowsHide: true });
    this.proc = p;
    p.stdout?.on("data", (d: Buffer) => this.feed(d));
    p.on("exit", () => {
      if (this.proc !== p) return;
      this.proc = null;
      this.carry = Buffer.alloc(0);
      if (this.subs.size) setTimeout(() => this.subs.size && this.ensure(), 1000);
    });
  }

  private feed(d: Buffer) {
    const b = this.carry.length ? Buffer.concat([this.carry, d]) : d;
    const usable = b.length - (b.length % framebytes);
    this.carry = Buffer.from(b.subarray(usable));
    for (let o = 0; o < usable; o += maxchunk) {
      const end = Math.min(o + maxchunk, usable);
      const out = Buffer.allocUnsafe(end - o + 1);
      out[0] = frame_audio;
      b.copy(out, 1, o, end);
      for (const s of this.subs) s(out);
    }
  }
}
