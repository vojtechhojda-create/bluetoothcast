import { icon } from "./icon.js";

export interface tray {
  setstatus(text: string): void;
  kill(): void;
}

export interface trayhandlers {
  panel(): void;
  pair(): void;
  clipboard(): void;
  files(): void;
  folder(): void;
  toggleopen(v: boolean): void;
  toggleopenfiles(v: boolean): void;
  toggleautostart(v: boolean): void;
  disconnect(): void;
  forget(): void;
  quit(): void;
}

export async function starttray(
  h: trayhandlers,
  openlinks: boolean,
  openfiles: boolean,
  autostart: boolean,
): Promise<tray | null> {
  try {
    const mod: any = await import("systray2");
    const SysTray = mod.default?.default ?? mod.default ?? mod;
    const item = (title: string, checked = false, enabled = true) => ({ title, tooltip: "", enabled, checked });
    const defs: [string, ReturnType<typeof item>][] = [
      ["status", item("proxylink", false, false)],
      ["panel", item("open panel")],
      ["pair", item("pair new device (qr)")],
      ["clipboard", item("send clipboard to phone")],
      ["files", item("send file to phone...")],
      ["folder", item("open received folder")],
      ["openfiles", item("open received files", openfiles)],
      ["openlinks", item("open links from phone", openlinks)],
      ["autostart", item("start with windows", autostart)],
      ["disconnect", item("disconnect phones")],
      ["forget", item("forget all devices")],
      ["quit", item("quit")],
    ];
    const items = defs.map((d) => d[1]);
    const t = new SysTray({ menu: { icon, title: "", tooltip: "proxylink", items }, debug: false, copyDir: true });
    await t.ready();
    const toggle = (i: number, cb: (v: boolean) => void) => {
      items[i].checked = !items[i].checked;
      t.sendAction({ type: "update-item", item: items[i], seq_id: i });
      cb(items[i].checked);
    };
    t.onClick((a: any) => {
      const i = a.seq_id as number;
      switch (defs[i]?.[0]) {
        case "panel": return h.panel();
        case "pair": return h.pair();
        case "clipboard": return h.clipboard();
        case "files": return h.files();
        case "folder": return h.folder();
        case "openfiles": return toggle(i, h.toggleopenfiles);
        case "openlinks": return toggle(i, h.toggleopen);
        case "autostart": return toggle(i, h.toggleautostart);
        case "disconnect": return h.disconnect();
        case "forget": return h.forget();
        case "quit": return h.quit();
      }
    });
    return {
      setstatus(text) {
        items[0].title = text.slice(0, 60);
        t.sendAction({ type: "update-item", item: items[0], seq_id: 0 });
      },
      kill() {
        t.kill(false);
      },
    };
  } catch (e) {
    console.warn("tray unavailable:", (e as Error).message);
    return null;
  }
}
