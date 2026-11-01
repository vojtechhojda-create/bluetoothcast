// proxylink <-> twocode workspace bridge.
// talks to the pc daemon on ws://127.0.0.1:8788/dev (channel "twocode") and gives the phone:
//   workspaces (name, git branch, dirty files), run predefined tasks, live output, stop.
// the phone can only pick a workspace id and a task name from twocode.json, never send a raw command.
import { execFile, spawn } from "node:child_process";
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import WebSocket from "ws";

const dir = join(process.env.APPDATA ?? join(homedir(), ".config"), "proxylink");
const cfgpath = process.env.PROXYLINK_TWOCODE ?? join(dir, "twocode.json");
const url = "ws://127.0.0.1:8788/dev";
const ch = "twocode";
const maxout = 65536;

const loadcfg = () => {
  try {
    const c = JSON.parse(readFileSync(cfgpath, "utf8"));
    return { workspaces: Array.isArray(c.workspaces) ? c.workspaces : [], timeoutsec: c.timeoutsec ?? 600 };
  } catch {
    return { workspaces: [], timeoutsec: 600 };
  }
};

const git = (cwd, args) =>
  new Promise((res) =>
    execFile("git", args, { cwd, timeout: 4000, windowsHide: true }, (e, out) => res(e ? null : out.trim())),
  );

async function info(w) {
  const branch = await git(w.path, ["rev-parse", "--abbrev-ref", "HEAD"]);
  const st = await git(w.path, ["status", "--porcelain"]);
  return {
    id: String(w.id),
    name: String(w.name ?? w.id),
    branch: branch ?? "",
    dirty: st ? st.split("\n").filter(Boolean).length : 0,
    tasks: Object.keys(w.tasks ?? {}),
  };
}

const runs = new Map();

function killtree(proc) {
  if (process.platform === "win32") {
    execFile("taskkill", ["/pid", String(proc.pid), "/T", "/F"], { windowsHide: true }, () => {});
  } else {
    try {
      process.kill(-proc.pid, "SIGTERM");
    } catch {
      proc.kill("SIGTERM");
    }
  }
}

function connect() {
  const token = (() => {
    try {
      return readFileSync(join(dir, "dev-token.txt"), "utf8").trim();
    } catch {
      return "";
    }
  })();
  const ws = new WebSocket(url);
  const reply = (to, data) => ws.readyState === 1 && ws.send(JSON.stringify({ type: "chan.send", ch, to, data }));

  ws.on("open", () => ws.send(JSON.stringify({ type: "hello", token, channels: [ch] })));

  ws.on("message", async (raw) => {
    let m;
    try {
      m = JSON.parse(raw.toString());
    } catch {
      return;
    }
    if (m.type === "hello.ok") return console.log("twocode bridge connected");
    if (m.type !== "chan.msg" || m.ch !== ch || typeof m.data !== "object" || !m.data) return;
    const from = m.from;
    const d = m.data;
    const cfg = loadcfg();

    if (d.op === "workspaces") {
      const items = await Promise.all(cfg.workspaces.map(info));
      return reply(from, { op: "workspaces", items });
    }

    if (d.op === "stop") {
      const r = runs.get(String(d.id));
      if (r && r.from === from) killtree(r.proc);
      return;
    }

    if (d.op === "run") {
      const id = String(d.id ?? Date.now()).slice(0, 40);
      const w = cfg.workspaces.find((x) => String(x.id) === String(d.workspace));
      const cmd = w?.tasks?.[d.task];
      if (!w || typeof cmd !== "string") return reply(from, { op: "run.done", id, code: -1, error: "unknown task" });
      if ([...runs.values()].some((r) => r.workspace === String(w.id)))
        return reply(from, { op: "run.done", id, code: -1, error: "workspace busy" });

      const proc = spawn(cmd, { cwd: w.path, shell: true, windowsHide: true, detached: process.platform !== "win32" });
      runs.set(id, { proc, workspace: String(w.id), from });
      reply(from, { op: "run.start", id, workspace: String(w.id), task: String(d.task) });

      let buffered = "";
      let total = 0;
      let timer = null;
      const flush = () => {
        timer = null;
        if (buffered) reply(from, { op: "run.out", id, text: buffered });
        buffered = "";
      };
      const onchunk = (chunk) => {
        if (total >= maxout) return;
        let t = chunk.toString("utf8");
        total += t.length;
        if (total >= maxout) t += "\n[output truncated]\n";
        buffered += t;
        if (buffered.length > 4000) {
          clearTimeout(timer);
          flush();
        } else if (!timer) timer = setTimeout(flush, 200);
      };
      proc.stdout.on("data", onchunk);
      proc.stderr.on("data", onchunk);
      const limit = setTimeout(() => killtree(proc), cfg.timeoutsec * 1000);
      let finished = false;
      const done = (code, error) => {
        if (finished) return;
        finished = true;
        clearTimeout(limit);
        clearTimeout(timer);
        flush();
        runs.delete(id);
        reply(from, { op: "run.done", id, code: code ?? -1, error });
      };
      proc.on("close", (code) => done(code));
      proc.on("error", (e) => done(-1, e.message));
    }
  });

  ws.on("close", () => setTimeout(connect, 2000));
  ws.on("error", () => {});
}

connect();
