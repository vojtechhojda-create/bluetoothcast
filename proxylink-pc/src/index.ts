import { Bonjour } from "bonjour-service";
import qrcode from "qrcode-terminal";
import { config, datadir, save } from "./config.js";
import { startlocal } from "./local.js";
import { fresh, payload, pcname } from "./pairing.js";
import { port_local, port_phone } from "./protocol.js";
import {
  closeall,
  devicelist,
  disconnectall,
  events,
  forgetall,
  pushto,
  sendfiles,
  startphoneserver,
} from "./server.js";
import { starttray } from "./tray.js";
import {
  autostartenabled,
  getclipboard,
  openfile,
  openpath,
  openurl,
  pickfiles,
  setautostart,
  shutdown as stopwin,
  warmup,
} from "./winsys.js";

const fatal = (e: NodeJS.ErrnoException) => {
  console.error(
    e.code === "EADDRINUSE" ? "proxylink is already running (port in use)" : `fatal: ${e.message}`,
  );
  process.exit(1);
};

fresh();
warmup();

const phone = startphoneserver(fatal);
const local = startlocal(fatal);
const bonjour = new Bonjour();
const service = bonjour.publish({ name: `proxylink-${pcname}`, type: "proxylink", protocol: "tcp", port: port_phone });

let tray: Awaited<ReturnType<typeof starttray>> = null;

const status = () => {
  const n = devicelist().length;
  tray?.setstatus(n ? `${n} phone${n > 1 ? "s" : ""} connected` : "no phone connected");
};

events.on("change", status);
events.on("info", (t: string) => {
  console.log(t);
  tray?.setstatus(t);
  setTimeout(status, 4000).unref();
});

let batch: string[] = [];
let batchtimer: NodeJS.Timeout | undefined;
events.on("received", (path: string) => {
  batch.push(path);
  clearTimeout(batchtimer);
  batchtimer = setTimeout(() => {
    const got = batch;
    batch = [];
    if (!config.openfiles) return;
    if (got.length === 1) openfile(got[0]);
    else openpath(config.downloaddir);
  }, 700);
});

const quit = () => {
  tray?.kill();
  closeall();
  stopwin();
  local.close();
  phone.close();
  service.stop?.(() => bonjour.destroy());
  setTimeout(() => process.exit(0), 800).unref();
};

tray = await starttray(
  {
    pair: () => void openurl(`http://127.0.0.1:${port_local}/pair`),
    clipboard: async () => {
      const t = (await getclipboard().catch(() => "")).trim();
      if (!t) return;
      pushto(/^https?:\/\/\S+$/i.test(t) ? "link" : "text", t, "clipboard");
    },
    files: async () => {
      const paths = await pickfiles().catch(() => []);
      if (paths.length) await sendfiles(paths);
    },
    folder: () => openpath(config.downloaddir),
    toggleopen: (v) => {
      config.openlinks = v;
      save();
    },
    toggleopenfiles: (v) => {
      config.openfiles = v;
      save();
    },
    toggleautostart: (v) => void setautostart(v).catch(() => {}),
    disconnect: disconnectall,
    forget: forgetall,
    quit,
  },
  config.openlinks,
  config.openfiles,
  await autostartenabled(),
);
status();

console.log(`proxylink on :${port_phone} (data: ${datadir})`);
qrcode.generate(payload(), { small: true });
console.log(`new qr: tray menu or http://127.0.0.1:${port_local}/pair`);

process.on("SIGINT", quit);
process.on("SIGTERM", quit);
