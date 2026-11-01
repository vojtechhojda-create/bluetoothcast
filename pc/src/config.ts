import { randomBytes } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

export const root = join(dirname(fileURLToPath(import.meta.url)), "..");
export const datadir = join(process.env.APPDATA ?? join(homedir(), ".config"), "proxylink");
mkdirSync(datadir, { recursive: true });

const file = join(datadir, "config.json");

export interface device {
  name: string;
  lastseen: number;
  pubkey?: string;
}

interface data {
  devices: Record<string, device>;
  openlinks: boolean;
  openfiles: boolean;
  panelapp: boolean;
  downloaddir: string;
}

function load(): data {
  const d: data = { devices: {}, openlinks: true, openfiles: true, panelapp: true, downloaddir: join(homedir(), "Downloads", "proxylink") };
  try {
    return { ...d, ...JSON.parse(readFileSync(file, "utf8")) };
  } catch {
    return d;
  }
}

export const config = load();

export const save = () => writeFileSync(file, JSON.stringify(config, null, 2));

const tokenfile = join(datadir, "dev-token.txt");

export const devtoken: string = (() => {
  try {
    const t = readFileSync(tokenfile, "utf8").trim();
    if (t.length >= 32) return t;
  } catch {}
  const t = randomBytes(24).toString("hex");
  writeFileSync(tokenfile, t, { mode: 0o600 });
  return t;
})();
