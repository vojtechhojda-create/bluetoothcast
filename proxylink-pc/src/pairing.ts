import { randomBytes, timingSafeEqual } from "node:crypto";
import { hostname, networkInterfaces } from "node:os";
import { tls } from "./cert.js";
import { port_phone } from "./protocol.js";

export const pcname = hostname();

const ttl = 10 * 60_000;
let token = "";
let expires = 0;
let fails = 0;

export function fresh(): string {
  token = randomBytes(16).toString("hex");
  expires = Date.now() + ttl;
  fails = 0;
  return token;
}

export function check(input: unknown): boolean {
  if (!token || Date.now() > expires || typeof input !== "string") return false;
  const a = Buffer.from(input);
  const b = Buffer.from(token);
  const ok = a.length === b.length && timingSafeEqual(a, b);
  if (ok) token = "";
  else if (++fails >= 5) token = "";
  return ok;
}

const virtual = /vethernet|virtualbox|vmware|wsl|hyper-v|loopback|docker|vpn|tailscale|zerotier|bluetooth/i;

export function lanip(): string | undefined {
  const found: string[] = [];
  for (const [name, list] of Object.entries(networkInterfaces())) {
    if (virtual.test(name)) continue;
    for (const i of list ?? []) {
      if (!i.internal && String(i.family).replace("IPv", "") === "4") found.push(i.address);
    }
  }
  return (
    found.find((a) => a.startsWith("192.168.")) ??
    found.find((a) => a.startsWith("10.")) ??
    found.find((a) => /^172\.(1[6-9]|2\d|3[01])\./.test(a)) ??
    found[0]
  );
}

export function payload(): string {
  if (!token || Date.now() > expires) fresh();
  return JSON.stringify({
    name: pcname,
    port: port_phone,
    token,
    fp: tls.fp,
    host: process.env.PROXYLINK_NOHOST ? undefined : lanip(),
  });
}
