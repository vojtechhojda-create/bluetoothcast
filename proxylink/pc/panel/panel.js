const $ = (s) => document.querySelector(s);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let ws = null;
let state = null;
let apps = [];
let inflight = 0;
let waiters = {};
let lastphones = "";
let retry = 0;

function h(tag, props, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (k === "class") e.className = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else if (v !== undefined && v !== false) e.setAttribute(k, v === true ? "" : v);
  }
  for (const c of kids.flat()) if (c != null) e.append(c.nodeType ? c : document.createTextNode(String(c)));
  return e;
}

const send = (o) => ws && ws.readyState === 1 && ws.send(JSON.stringify(o));

function connect() {
  ws = new WebSocket(`ws://${location.host}/panel-ws`);
  ws.binaryType = "arraybuffer";
  ws.onopen = () => {
    retry = 0;
    const m = /[#&]t=([0-9a-f]+)/.exec(location.hash);
    const session = sessionStorage.getItem("session") || undefined;
    if (m) send({ type: "hello", ticket: m[1], session });
    else if (session) send({ type: "hello", session });
    else {
      $("#offline").classList.remove("hidden");
      return;
    }
    history.replaceState(null, "", location.pathname);
  };
  ws.onmessage = (e) => {
    let m;
    try { m = JSON.parse(e.data); } catch { return; }
    if (m.type === "hello.ok") {
      sessionStorage.setItem("session", m.session);
      $("#offline").classList.add("hidden");
      send({ type: "apps" });
    } else if (m.type === "state") {
      state = m;
      render();
    } else if (m.type === "apps") {
      apps = m.apps;
      lastphones = "";
      render();
    } else if (m.type === "file.ack") {
      inflight = Math.max(0, inflight - 1);
    } else if (m.type === "sent") {
      say(m.ok ? "sent" : "no phone connected");
    } else if (m.type === "file.begin" || m.type === "file.end") {
      const w = waiters[m.type];
      if (w) { delete waiters[m.type]; w(m.ok !== false); }
    }
  };
  ws.onclose = (e) => {
    state = null;
    setpill(false);
    if (e.code === 4403) {
      sessionStorage.removeItem("session");
      $("#offline").classList.remove("hidden");
      return;
    }
    setTimeout(connect, Math.min(5000, 500 * ++retry));
  };
}

function setpill(on, text) {
  const p = $("#pill");
  p.className = "pill " + (on ? "on" : "off");
  p.textContent = text || (on ? "connected" : "offline");
}

function say(t) {
  $("#progress").textContent = t;
  setTimeout(() => { if ($("#progress").textContent === t) $("#progress").textContent = ""; }, 4000);
}

function throttle(fn, ms) {
  let t = 0;
  return (...a) => { const n = Date.now(); if (n - t >= ms) { t = n; fn(...a); } };
}

function slider(value, label, onchange) {
  const out = h("span", { class: "muted" }, `${label} ${Math.round(value * 100)}%`);
  const s = h("input", { type: "range", min: "0", max: "200", value: String(Math.round(value * 100)) });
  const push = throttle((v) => onchange(v / 100), 100);
  s.addEventListener("input", () => {
    out.textContent = `${label} ${s.value}%`;
    push(Number(s.value));
  });
  s.addEventListener("change", () => onchange(Number(s.value) / 100));
  return h("div", { class: "row" }, out, s);
}

function phonecard(p) {
  const sel = h("select", {}, h("option", { value: "0" }, "all pc audio"),
    apps.map((a) => h("option", { value: String(a.pid) }, a.name + (a.title ? ` - ${a.title.slice(0, 30)}` : "") + (a.active ? " *" : ""))));
  if (p.down && p.down.pid) sel.value = String(p.down.pid);

  const downblock = h("div", { class: "block" },
    h("h4", {}, "pc audio to this phone"),
    h("div", { class: "row" }, sel,
      p.down
        ? h("button", { class: "danger", onclick: () => send({ type: "down.stop", device: p.id }) }, "stop")
        : h("button", { class: "primary", disabled: !state.hasaudio, onclick: () => send({ type: "down.start", device: p.id, pid: Number(sel.value) || undefined }) }, "start"),
      p.down ? h("span", { class: "eq" }, h("i"), h("i"), h("i"), h("i")) : null,
      h("button", { onclick: () => send({ type: "apps" }) }, "refresh apps")),
    slider(p.downgain, "volume", (g) => send({ type: "down.gain", device: p.id, gain: g })),
    state.hasaudio ? null : h("div", { class: "muted" }, "audio helper missing: run install.bat with the .net sdk installed"));

  const upblock = h("div", { class: "block" },
    h("h4", {}, "phone audio to this pc"),
    h("div", { class: "row" },
      p.up
        ? [h("span", { class: "eq" }, h("i"), h("i"), h("i"), h("i")), h("span", {}, `receiving: ${p.up.source}`),
           h("button", { class: "danger", onclick: () => send({ type: "up.stop", device: p.id }) }, "stop")]
        : h("span", { class: "muted" }, "idle. start it in the phone app (phone mic or phone audio)")),
    slider(p.upgain, "volume", (g) => send({ type: "up.gain", device: p.id, gain: g })));

  return h("div", { class: "card phone" },
    h("div", { class: "phone-head" }, h("span", { class: "dot" }), h("h3", { class: "grow" }, p.name),
      h("button", { onclick: () => send({ type: "disconnect", device: p.id }) }, "disconnect")),
    downblock, upblock);
}

function render() {
  if (!state) return;
  $("#pc").textContent = state.pc;
  const n = state.phones.length;
  setpill(n > 0, n ? `${n} phone${n > 1 ? "s" : ""}` : "no phone");

  const sig = JSON.stringify(state.phones) + JSON.stringify(apps) + state.hasaudio;
  if (sig !== lastphones) {
    lastphones = sig;
    const box = $("#phones");
    box.replaceChildren(
      n ? state.phones.map(phonecard)
        : h("div", { class: "card" }, h("h2", {}, "no phone connected"),
            h("p", { class: "muted" }, "open the proxylink app on your phone, or pair a new one from the tray menu: pair new device.")));
    const t = $("#target");
    const cur = t.value;
    t.replaceChildren(h("option", { value: "" }, "all phones"), state.phones.map((p) => h("option", { value: p.id }, p.name)));
    t.value = [...t.options].some((o) => o.value === cur) ? cur : "";
    $("#sendcard").classList.toggle("hidden", n === 0);
  }
  $("#openlinks").checked = state.settings.openlinks;
  $("#openfiles").checked = state.settings.openfiles;
  $("#log").replaceChildren(...state.log.slice().reverse().map((l) => h("li", {}, `${new Date(l.t).toLocaleTimeString()}  ${l.text}`)));
}

$("#openlinks").addEventListener("change", (e) => send({ type: "settings", openlinks: e.target.checked }));
$("#openfiles").addEventListener("change", (e) => send({ type: "settings", openfiles: e.target.checked }));

$("#sendtext").addEventListener("click", () => {
  const data = $("#text").value.trim();
  if (!data) return;
  send({ type: "send.text", device: $("#target").value, data });
  $("#text").value = "";
});

const waitfor = (type) => new Promise((res) => { waiters[type] = res; setTimeout(() => { if (waiters[type] === res) { delete waiters[type]; res(false); } }, 5000); });

async function uploadone(device, file) {
  send({ type: "file.begin", device, name: file.name, size: file.size });
  if (!(await waitfor("file.begin"))) return say(`could not send ${file.name}`);
  const chunk = 64 * 1024;
  for (let off = 0; off < file.size; off += chunk) {
    while (inflight >= 8 || ws.bufferedAmount > 2_000_000) await sleep(10);
    ws.send(await file.slice(off, off + chunk).arrayBuffer());
    inflight++;
    say(`${file.name}: ${Math.min(100, Math.round(((off + chunk) / file.size) * 100))}%`);
  }
  while (inflight > 0) await sleep(10);
  send({ type: "file.end" });
  await waitfor("file.end");
  say(`sent ${file.name}`);
}

async function upload(files) {
  const target = $("#target").value;
  const devices = target ? [target] : state.phones.map((p) => p.id);
  for (const f of files) for (const d of devices) await uploadone(d, f);
}

$("#pick").addEventListener("click", () => $("#files").click());
$("#files").addEventListener("change", (e) => { upload([...e.target.files]); e.target.value = ""; });

const drop = $("#drop");
for (const ev of ["dragenter", "dragover"]) drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.add("over"); });
for (const ev of ["dragleave", "drop"]) drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.remove("over"); });
drop.addEventListener("drop", (e) => upload([...e.dataTransfer.files]));
window.addEventListener("dragover", (e) => e.preventDefault());
window.addEventListener("drop", (e) => e.preventDefault());

connect();
