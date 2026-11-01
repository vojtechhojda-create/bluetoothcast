const api = typeof browser !== "undefined" ? browser : chrome;
const $ = (id) => document.getElementById(id);

function say(t) {
  $("msg").textContent = t;
}

async function refresh() {
  const r = await rpc({ type: "status" });
  const s = $("status");
  const ok = r.type === "status" && r.devices.length > 0;
  if (r.type !== "status") s.textContent = r.error || "proxylink pc app not running";
  else s.textContent = ok ? `connected: ${r.devices.map((d) => d.name).join(", ")}` : "no phone connected";
  s.className = ok ? "ok" : "bad";
  const sel = $("device");
  sel.innerHTML = "";
  if (r.type === "status" && r.devices.length > 1) {
    sel.add(new Option("all phones", ""));
    r.devices.forEach((d) => sel.add(new Option(d.name, d.id)));
    sel.hidden = false;
  } else {
    sel.hidden = true;
  }
  $("page").disabled = $("sendtext").disabled = !ok;
}

async function send(kind, data, title) {
  if (!data) return;
  say("sending...");
  const r = await rpc({ type: "send", kind, data, title: title || "", device: $("device").value || undefined });
  say(r.ok ? "sent" : r.error || "failed");
  if (r.ok) setTimeout(() => window.close(), 600);
}

$("page").onclick = async () => {
  const [tab] = await api.tabs.query({ active: true, currentWindow: true });
  if (tab) send("link", tab.url, tab.title);
};

$("sendtext").onclick = () => send("text", $("text").value.trim(), "");

refresh();
