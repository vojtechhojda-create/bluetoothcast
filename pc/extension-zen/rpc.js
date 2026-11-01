const proxylink_url = "ws://127.0.0.1:8788/ext";

function rpc(msg, timeout = 3000) {
  return new Promise((resolve) => {
    let done = false;
    let ws;
    let timer;
    const finish = (r) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      try { ws.close(); } catch {}
      resolve(r);
    };
    timer = setTimeout(() => finish({ ok: false, error: "proxylink pc app not running. start it with start.bat" }), timeout);
    try {
      ws = new WebSocket(proxylink_url);
    } catch {
      return finish({ ok: false, error: "proxylink pc app not running. start it with start.bat" });
    }
    ws.onopen = () => ws.send(JSON.stringify(msg));
    ws.onmessage = (e) => {
      try { finish(JSON.parse(e.data)); } catch { finish({ ok: false, error: "bad reply" }); }
    };
    ws.onerror = () => finish({ ok: false, error: "proxylink pc app not running. start it with start.bat" });
  });
}
