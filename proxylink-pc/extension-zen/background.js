const api = typeof browser !== "undefined" ? browser : chrome;

async function notify(message) {
  try {
    await api.notifications.create({
      type: "basic",
      iconUrl: api.runtime.getURL("icon-96.png"),
      title: "proxylink",
      message,
    });
  } catch {}
}

async function send(kind, data, title) {
  if (!data) return;
  const r = await rpc({ type: "send", kind, data, title: title || "" });
  notify(r.ok ? "sent to phone" : r.error || "failed");
}

api.runtime.onInstalled.addListener(() => {
  const items = [
    { id: "page", title: "send page to phone", contexts: ["page", "frame"] },
    { id: "link", title: "send link to phone", contexts: ["link"] },
    { id: "image", title: "send image to phone", contexts: ["image"] },
    { id: "selection", title: "send selection to phone", contexts: ["selection"] },
  ];
  api.contextMenus.removeAll().then(() => items.forEach((i) => api.contextMenus.create(i)));
});

api.contextMenus.onClicked.addListener((info, tab) => {
  switch (info.menuItemId) {
    case "page":
      return send("link", info.pageUrl || (tab && tab.url), tab && tab.title);
    case "link":
      return send("link", info.linkUrl, info.linkText);
    case "image":
      return send("link", info.srcUrl, "image");
    case "selection":
      return send("text", info.selectionText, tab && tab.title);
  }
});

api.commands.onCommand.addListener(async (command) => {
  if (command !== "send-page") return;
  const [tab] = await api.tabs.query({ active: true, currentWindow: true });
  if (tab) send("link", tab.url, tab.title);
});
