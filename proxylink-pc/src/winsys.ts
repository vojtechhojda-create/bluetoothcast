import { execFile, spawn, type ChildProcess } from "node:child_process";
import { extname, join } from "node:path";
import { root } from "./config.js";

const win = process.platform === "win32";
const base = ["-NoProfile", "-NonInteractive", "-STA", "-ExecutionPolicy", "Bypass"];
const utf8 = "[Console]::OutputEncoding=[Text.Encoding]::UTF8;";

const ps = (script: string, env: Record<string, string> = {}) =>
  new Promise<string>((res, rej) => {
    if (!win) return rej(new error_unsupported());
    execFile(
      "powershell.exe",
      [...base, "-Command", utf8 + script],
      { env: { ...process.env, ...env }, windowsHide: true, maxBuffer: 16 * 1024 * 1024, encoding: "utf8" },
      (e, out) => (e ? rej(e) : res(out)),
    );
  });

class error_unsupported extends Error {
  constructor() {
    super("windows only");
  }
}

let helper: ChildProcess | null = null;

function gethelper(): ChildProcess | null {
  if (!win) return null;
  if (helper && helper.exitCode === null && !helper.killed) return helper;
  const h = spawn("powershell.exe", [...base, "-File", join(root, "scripts", "keys.ps1")], {
    windowsHide: true,
    stdio: ["pipe", "ignore", "ignore"],
  });
  h.on("exit", () => {
    if (helper === h) helper = null;
  });
  h.stdin?.on("error", () => {});
  helper = h;
  return h;
}

export const warmup = () => void gethelper();
export const shutdown = () => helper?.kill();

const keys: Record<string, number> = {
  play_pause: 0xb3,
  next: 0xb0,
  prev: 0xb1,
  vol_up: 0xaf,
  vol_down: 0xae,
  mute: 0xad,
};

export function mediakey(action: unknown): boolean {
  const k = typeof action === "string" ? keys[action] : undefined;
  if (!k) return false;
  gethelper()?.stdin?.write(`key ${k}\n`);
  return true;
}

export function setclipboard(text: string) {
  gethelper()?.stdin?.write(`setclip ${Buffer.from(text, "utf8").toString("base64")}\n`);
}

export async function getclipboard(): Promise<string> {
  return ps("$t = Get-Clipboard -Raw; if ($t) { [Console]::Out.Write($t) }");
}

export async function askyesno(title: string, msg: string): Promise<boolean> {
  const out = await ps(
    "Add-Type -AssemblyName System.Windows.Forms; " +
      "$f = New-Object System.Windows.Forms.Form -Property @{TopMost=$true}; " +
      "$r = [System.Windows.Forms.MessageBox]::Show($f, $env:PL_MSG, $env:PL_TITLE, 'YesNo', 'Question'); " +
      "Write-Output $r",
    { PL_MSG: msg, PL_TITLE: title },
  );
  return out.trim() === "Yes";
}

export async function pickfiles(): Promise<string[]> {
  const out = await ps(
    "Add-Type -AssemblyName System.Windows.Forms; " +
      "$f = New-Object System.Windows.Forms.Form -Property @{TopMost=$true}; " +
      "$d = New-Object System.Windows.Forms.OpenFileDialog -Property @{Multiselect=$true}; " +
      "if ($d.ShowDialog($f) -eq 'OK') { $d.FileNames -join [char]10 }",
  );
  return out.split(/\r?\n/).filter(Boolean);
}

export function openurl(raw: string): boolean {
  let u: URL;
  try {
    u = new URL(raw);
  } catch {
    return false;
  }
  if (u.protocol !== "http:" && u.protocol !== "https:") return false;
  if (!win) return false;
  spawn("rundll32.exe", ["url.dll,FileProtocolHandler", u.href], { windowsHide: true, stdio: "ignore" }).unref();
  return true;
}

export function openpath(p: string) {
  if (win) spawn("explorer.exe", [p], { stdio: "ignore" }).unref();
}

const runkey = "HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

export async function autostartenabled(): Promise<boolean> {
  try {
    const o = await ps(`(Get-ItemProperty -Path '${runkey}' -Name proxylink -ErrorAction SilentlyContinue).proxylink`);
    return o.trim().length > 0;
  } catch {
    return false;
  }
}

export async function setautostart(on: boolean): Promise<void> {
  if (on) {
    await ps(`Set-ItemProperty -Path '${runkey}' -Name proxylink -Value $env:PL_VAL`, {
      PL_VAL: `wscript.exe "${join(root, "scripts", "start-hidden.vbs")}"`,
    });
  } else {
    await ps(`Remove-ItemProperty -Path '${runkey}' -Name proxylink -ErrorAction SilentlyContinue`);
  }
}

const safeopen = new Set([
  ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".pdf", ".txt", ".md", ".csv", ".json", ".log",
  ".mp3", ".wav", ".flac", ".ogg", ".m4a", ".mp4", ".mkv", ".webm", ".mov", ".avi",
  ".docx", ".xlsx", ".pptx", ".odt", ".ods", ".odp", ".rtf",
]);

export function openfile(p: string) {
  if (process.platform !== "win32") return;
  if (safeopen.has(extname(p).toLowerCase())) {
    spawn("rundll32.exe", ["url.dll,FileProtocolHandler", p], { windowsHide: true, stdio: "ignore" }).unref();
  } else {
    spawn("explorer.exe", [`/select,"${p}"`], { windowsVerbatimArguments: true, stdio: "ignore" }).unref();
  }
}
