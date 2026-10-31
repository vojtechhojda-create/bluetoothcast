"""
ProxyLink - PC odesilac zvuku do telefonu (Windows).

Instalace:  pip install pyaudiowpatch numpy pystray pillow
Spusteni:   python proxylink_pc.py                    (otevre okno)
            pythonw proxylink_pc.py --background      (jen ikona v liste; takhle se spousti s Windows)
            python proxylink_pc.py --list             (vypise zdroje zvuku)

Nastaveni: %APPDATA%\\ProxyLink\\settings.json

PROTOKOL (TCP 8766, radky zakoncene \\n, JSON; telefon je server)
  discovery   PC -> UDP broadcast :8766 "PROXYLINK?"      telefon -> "PROXYLINK!"
  PC -> tel   {"name":"<PC>","rate":48000,"channels":2,"token":"<ulozeny, pokud existuje>"}
  tel -> PC   {"status":"ok","name":"<telefon>"}          token platny, hned se streamuje
              {"status":"pair","name":"<telefon>"}        telefon otevre stranku s jednorazovym kodem
              {"status":"denied"}                         zamitnuto
  pri "pair"  PC -> tel   {"code":"123456"}
              tel -> PC   {"status":"paired","token":"<tajny>","name":"<telefon>"}  nebo {"status":"denied"}
  potom       PC posila surove PCM int16 stereo (rate z hello); telefon ukonci spojeni zavrenim socketu.
  barva       hue = CRC32(utf8(jmeno)) % 360, HLS(hue, 0.58, 0.65) - stejne na PC i v telefonu
              (PC se barví podle jmena telefonu, telefon podle jmena PC).
"""
import argparse
import colorsys
import json
import os
import queue
import socket
import subprocess
import sys
import threading
import time
import zlib
import tkinter as tk
from tkinter import messagebox

import numpy as np
import pyaudiowpatch as pyaudio
from PIL import Image, ImageDraw

APP = "ProxyLink"
PORT = 8766
LOCK_PORT = 47866
HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_DIR = os.path.join(os.environ.get("APPDATA", HERE), APP)
CONFIG = os.path.join(CONFIG_DIR, "settings.json")

DEFAULTS = {
    "phone_ip": "",        # posledni znama IP telefonu
    "phone_name": "",      # jmeno telefonu (z nej se odvozuje barva)
    "token": "",           # tajny token ziskany pri parovani
    "enabled": True,       # zapnuto = hleda telefon a streamuje
    "autostart": True,     # spoustet s Windows
    "device": "",          # cast nazvu zdroje zvuku; prazdne = vychozi vystup
    "latency": "phone",    # phone = podle telefonu | low | balanced | stable
    "retry_delay_s": 3,
    "chunk_ms": 20,        # nahradni hodnoty, kdyz telefon latenci neposle
    "queue_size": 6,
    "_napoveda": "latency: phone (nastavuje telefon) | low | balanced | stable. "
                 "device: cast nazvu zdroje zvuku (python proxylink_pc.py --list). "
                 "Po ulozeni souboru se zmeny nactou samy.",
}

BG, CARD, FG, MUTED, BTN2 = "#14161c", "#1d2028", "#e8eaf0", "#8b90a0", "#2a2e3a"
LATENCY = {"low": (10, 60), "balanced": (20, 120), "stable": (40, 300)}   # (chunk_ms, buffer_ms)
LEVELS = {"off": "#5b6070", "search": "#f0b429", "ok": "#3ddc97", "error": "#ef5b5b"}


# ---------- nastaveni ----------

def load_settings():
    s = dict(DEFAULTS)
    try:
        with open(CONFIG, encoding="utf-8-sig") as f:
            data = json.load(f)
        if isinstance(data, dict):
            s.update(data)
    except (OSError, ValueError):
        pass
    return s


def make_saver(settings):
    lock = threading.Lock()

    def save():
        with lock:
            try:
                os.makedirs(CONFIG_DIR, exist_ok=True)
                with open(CONFIG, "w", encoding="utf-8") as f:
                    json.dump(settings, f, indent=2, ensure_ascii=False)
            except OSError:
                pass
    return save


def num(settings, key, minimum=0):
    try:
        v = int(settings[key])
    except (TypeError, ValueError):
        v = DEFAULTS[key]
    return max(v, minimum)


def set_autostart(on):
    """Zapis/smazani polozky v HKCU\\...\\Run (jen Windows)."""
    try:
        import winreg
    except ImportError:
        return
    if getattr(sys, "frozen", False):
        cmd = f'"{sys.executable}" --background'
    else:
        exe = sys.executable
        pyw = os.path.join(os.path.dirname(exe), "pythonw.exe")
        if os.path.exists(pyw):
            exe = pyw
        cmd = f'"{exe}" "{os.path.abspath(__file__)}" --background'
    key = winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                         r"Software\Microsoft\Windows\CurrentVersion\Run", 0, winreg.KEY_SET_VALUE)
    try:
        if on:
            winreg.SetValueEx(key, APP, 0, winreg.REG_SZ, cmd)
        else:
            try:
                winreg.DeleteValue(key, APP)
            except FileNotFoundError:
                pass
    finally:
        winreg.CloseKey(key)


# ---------- barvy a ikona ----------

def accent(name):
    hue = zlib.crc32((name or APP).encode("utf-8")) % 360
    r, g, b = colorsys.hls_to_rgb(hue / 360, 0.58, 0.65)
    return "#%02x%02x%02x" % (round(r * 255), round(g * 255), round(b * 255))


def shade(hexc, factor):
    r, g, b = (int(hexc[i:i + 2], 16) / 255 for i in (1, 3, 5))
    h, l, s = colorsys.rgb_to_hls(r, g, b)
    r, g, b = colorsys.hls_to_rgb(h, min(1.0, l * factor), s)
    return "#%02x%02x%02x" % (round(r * 255), round(g * 255), round(b * 255))


def make_icon(color, dot=None, size=64):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    k = size / 64
    d.rounded_rectangle((2 * k, 2 * k, size - 3 * k, size - 3 * k), radius=16 * k, fill=color)
    w = max(2, round(6 * k))
    d.ellipse((9 * k, 19 * k, 38 * k, 48 * k), outline="white", width=w)
    d.ellipse((26 * k, 19 * k, 55 * k, 48 * k), outline="white", width=w)
    if dot:
        d.ellipse((40 * k, 40 * k, 61 * k, 61 * k), fill=dot, outline=BG, width=max(1, round(3 * k)))
    return img


# ---------- sit ----------

def send_json(obj):
    return (json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8")


def discover(tries=3, timeout=1.5):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.settimeout(timeout)
    try:
        for _ in range(tries):
            try:
                s.sendto(b"PROXYLINK?", ("255.255.255.255", PORT))
                data, addr = s.recvfrom(64)
                if data == b"PROXYLINK!":
                    return addr[0]
            except (socket.timeout, OSError):
                pass
    finally:
        s.close()
    return None


def try_connect(ip, timeout=3):
    try:
        s = socket.create_connection((ip, PORT), timeout=timeout)
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        return s
    except OSError:
        return None


def find_phone(settings):
    """Vrati (socket, ip) nebo (None, None)."""
    ip = str(settings.get("phone_ip") or "").strip()
    if ip:
        s = try_connect(ip)
        if s:
            return s, ip
    found = discover()
    if found and found != ip:
        s = try_connect(found)
        if s:
            return s, found
    return None, None


def recv_line(sock, limit=4096):
    buf = bytearray()
    while len(buf) < limit:
        c = sock.recv(1)
        if not c:
            raise OSError("spojeni zavreno")
        if c == b"\n":
            return buf.decode("utf-8", errors="ignore").strip()
        buf += c
    raise OSError("radek je prilis dlouhy")


def recv_msg(sock):
    line = recv_line(sock)
    if line.upper() == "OK":          # kompatibilita se starou odpovedi
        return {"status": "ok"}
    try:
        m = json.loads(line)
        return m if isinstance(m, dict) else {}
    except ValueError:
        return {}


# ---------- zvukova zarizeni ----------

def list_devices(p):
    wasapi = p.get_host_api_info_by_type(pyaudio.paWASAPI)
    default_out = p.get_device_info_by_index(wasapi["defaultOutputDevice"])
    devs = list(p.get_loopback_device_info_generator())
    default_idx = 0
    for i, d in enumerate(devs):
        if default_out["name"] in d["name"]:
            default_idx = i
            break
    return devs, default_idx


def pick_device(p, settings):
    devs, default_idx = list_devices(p)
    if not devs:
        raise RuntimeError("Nenalezen zdroj zvuku (WASAPI loopback)")
    want = str(settings.get("device") or "").strip().lower()
    if want:
        for d in devs:
            if want in d["name"].lower():
                return d
    return devs[default_idx]


def fmt_time(sec):
    return time.strftime("%H:%M:%S", time.gmtime(sec))


# ---------- streamovani (vlakno na pozadi) ----------

class Streamer(threading.Thread):
    def __init__(self, settings, save, post):
        super().__init__(daemon=True)
        self.s, self.save, self.post = settings, save, post
        self.enabled = threading.Event()
        self.quit = threading.Event()
        self.wake = threading.Event()
        self.code_q = queue.Queue()
        self._last_state = None
        self.remote = {}
        if settings.get("enabled"):
            self.enabled.set()
            self.set_state("Hledám telefon…", "search")
        else:
            self.set_state("Vypnuto", "off")

    # --- ovladani z okna / listy ---
    def set_state(self, text, level):
        if self._last_state != (text, level):
            self._last_state = (text, level)
            self.post("state", text=text, level=level)

    def set_enabled(self, value, text=None, level="off"):
        value = bool(value)
        if value:
            self.enabled.set()
        else:
            self.enabled.clear()
        self.s["enabled"] = value
        self.save()
        self.post("enabled", value=value)
        self.set_state("Hledám telefon…" if value else (text or "Vypnuto"),
                       "search" if value else level)
        self.wake.set()

    def unpair(self):
        self.s["token"] = self.s["phone_ip"] = self.s["phone_name"] = ""
        self.save()
        self.post("phone", name="", ip="")
        self.set_enabled(False, "Telefon odpojen")

    def stop(self):
        self.quit.set()
        self.wake.set()

    # --- parovani ---
    def wait_code(self, timeout=180):
        while not self.code_q.empty():
            self.code_q.get_nowait()
        end = time.time() + timeout
        while time.time() < end and self.enabled.is_set() and not self.quit.is_set():
            try:
                return self.code_q.get(timeout=0.3) or None
            except queue.Empty:
                pass
        return None

    def handshake(self, sock, hello):
        """Vrati (stav, jmeno_telefonu); stav: ok | denied | cancelled."""
        sock.settimeout(15)
        sock.sendall(send_json(hello))
        msg = recv_msg(sock)
        if msg.get("status") == "pair":
            self.set_state("Zadej kód z telefonu", "search")
            self.post("need_code", name=str(msg.get("name") or ""))
            code = self.wait_code()
            if not code:
                return "cancelled", ""
            sock.sendall(send_json({"code": code}))
            sock.settimeout(15)
            msg = recv_msg(sock)
            if msg.get("status") == "paired" and msg.get("token"):
                self.s["token"] = str(msg["token"])
                self.save()
                msg["status"] = "ok"
        if msg.get("status") != "ok":
            return "denied", ""
        self.remote = msg
        return "ok", str(msg.get("name") or self.s.get("phone_name") or "")

    # --- latence ---
    def latency_params(self):
        """(chunk_ms, buffer_ms): z nastaveni PC, jinak podle telefonu, jinak vychozi."""
        mode = str(self.s.get("latency") or "phone").lower()
        if mode in LATENCY:
            return LATENCY[mode]
        try:
            chunk = int(self.remote.get("chunk_ms") or num(self.s, "chunk_ms", 5))
            buf = int(self.remote.get("buffer_ms") or chunk * num(self.s, "queue_size", 2))
        except (TypeError, ValueError):
            chunk, buf = 20, 120
        chunk = min(max(chunk, 5), 100)
        return chunk, min(max(buf, chunk * 2), 2000)

    # --- jedna relace ---
    def session(self):
        """Vraci: no_phone | denied | cancelled | error | disconnected | stopped."""
        sock, ip = find_phone(self.s)
        if not sock:
            return "no_phone"
        p = pyaudio.PyAudio()
        stream = None
        stop = threading.Event()
        try:
            try:
                dev = pick_device(p, self.s)
            except Exception as e:
                self.set_state(f"Chyba zvuku: {e}", "error")
                return "error"
            rate = int(dev["defaultSampleRate"])
            in_ch = dev["maxInputChannels"]
            hello = {"name": socket.gethostname(), "rate": rate, "channels": 2}
            if self.s.get("token"):
                hello["token"] = self.s["token"]
            try:
                state, name = self.handshake(sock, hello)
            except OSError:
                return "no_phone"
            if state != "ok":
                return state
            sock.settimeout(None)
            chunk_ms, buf_ms = self.latency_params()
            try:   # mensi odesilaci buffer = zvuk se nehromadi, kdyz sit zvolni
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, max(4096, rate * 4 // 1000 * buf_ms))
            except OSError:
                pass

            if self.s.get("phone_ip") != ip or (name and self.s.get("phone_name") != name):
                self.s["phone_ip"] = ip
                if name:
                    self.s["phone_name"] = name
                self.save()
            self.post("phone", name=self.s.get("phone_name", ""), ip=ip)

            q = queue.Queue(maxsize=max(2, buf_ms // chunk_ms))
            stats = {"bytes": 0, "dropped": 0, "last": 0.0}

            def callback(in_data, frame_count, time_info, status):
                arr = np.frombuffer(in_data, dtype=np.int16)
                if in_ch != 2:
                    arr = arr.reshape(-1, in_ch)
                    arr = np.repeat(arr, 2, axis=1) if in_ch == 1 else arr[:, :2]
                    arr = np.ascontiguousarray(arr).reshape(-1)
                stats["last"] = time.time()
                data = arr.tobytes()
                try:
                    q.put_nowait(data)
                except queue.Full:          # zahodit nejstarsi, aby zvuk zustal aktualni
                    stats["dropped"] += 1
                    try:
                        q.get_nowait()
                        q.put_nowait(data)
                    except (queue.Empty, queue.Full):
                        pass
                return (None, pyaudio.paContinue)

            def sender():
                try:
                    while not stop.is_set():
                        try:
                            data = q.get(timeout=0.5)
                        except queue.Empty:
                            continue
                        sock.sendall(data)
                        stats["bytes"] += len(data)
                except OSError:
                    pass
                finally:
                    stop.set()

            threading.Thread(target=sender, daemon=True).start()
            stream = p.open(format=pyaudio.paInt16, channels=in_ch, rate=rate, input=True,
                            input_device_index=dev["index"],
                            frames_per_buffer=rate * chunk_ms // 1000,
                            stream_callback=callback)
            self.set_state("Streamuji do telefonu", "ok")

            start = last_t = time.time()
            last_bytes = 0
            while not stop.is_set() and self.enabled.is_set() and not self.quit.is_set():
                stop.wait(1.0)
                now = time.time()
                kbps = (stats["bytes"] - last_bytes) * 8 / 1000 / max(now - last_t, 0.001)
                last_t, last_bytes = now, stats["bytes"]
                self.post("stats", kbps=kbps, uptime=now - start, dropped=stats["dropped"],
                          playing=now - stats["last"] < 1.0)
            return "disconnected" if stop.is_set() else "stopped"
        finally:
            stop.set()
            if stream is not None:
                try:
                    stream.stop_stream()
                    stream.close()
                except Exception:
                    pass
            try:
                sock.close()
            except OSError:
                pass
            p.terminate()

    # --- hlavni smycka ---
    def run(self):
        while not self.quit.is_set():
            if not self.enabled.is_set():
                self.enabled.wait(0.5)
                continue
            try:
                result = self.session()
            except Exception as e:
                result = "error"
                self.set_state(f"Chyba: {e}", "error")
            if self.quit.is_set():
                break
            if not self.enabled.is_set():
                continue
            if result == "cancelled":
                self.set_enabled(False)
                continue
            if result == "denied":
                self.set_enabled(False, "Telefon spárování odmítl", "error")
                continue
            if result == "no_phone":
                self.set_state("Telefon nenalezen, zkouším znovu…", "search")
            elif result == "disconnected":
                self.set_state("Spojení ukončeno, připojuji znovu…", "search")
            self.wake.wait(num(self.s, "retry_delay_s", 1))
            self.wake.clear()


# ---------- okno ----------

class Btn(tk.Label):
    def __init__(self, parent, text, cmd, bg, fg="#ffffff", **kw):
        super().__init__(parent, text=text, bg=bg, fg=fg, font=("Segoe UI", 11, "bold"),
                         padx=16, pady=10, cursor="hand2", **kw)
        self.base = bg
        self.bind("<Button-1>", lambda e: cmd())
        self.bind("<Enter>", lambda e: self.config(bg=shade(self.base, 1.15)))
        self.bind("<Leave>", lambda e: self.config(bg=self.base))

    def recolor(self, bg):
        self.base = bg
        self.config(bg=bg)


class App:
    def __init__(self, settings, background, lock=None):
        self.s = settings
        self.save = make_saver(settings)
        self._tick = 0
        self._mtime = self.mtime()
        self.q = queue.Queue()
        self.status, self.level = "", "off"
        self.tray = None
        self.root = tk.Tk()
        self.root.title(APP)
        self.root.geometry("400x700")
        self.root.resizable(False, False)
        self.root.configure(bg=BG)
        self.root.protocol("WM_DELETE_WINDOW", self.hide)
        self.streamer = Streamer(settings, self.save, self.post)
        self.build()
        self.start_tray()
        self.refresh()
        if background and self.tray and settings.get("token"):
            self.root.withdraw()
        self.streamer.start()
        if lock:
            threading.Thread(target=self.lock_loop, args=(lock,), daemon=True).start()
        self.root.after(100, self.poll)

    def post(self, kind, **data):
        self.q.put((kind, data))

    def lock_loop(self, lock):
        while True:   # druha instance nam jen "zaklepe" - ukazeme okno
            try:
                c, _ = lock.accept()
                c.close()
                self.post("show")
            except OSError:
                return

    def mtime(self):
        try:
            return os.path.getmtime(CONFIG)
        except OSError:
            return 0

    def check_settings(self):
        """Zmeny v settings.json (z Poznamkoveho bloku) se nactou samy."""
        m = self.mtime()
        if not m or m == self._mtime:
            return
        try:
            with open(CONFIG, encoding="utf-8-sig") as f:
                data = json.load(f)
        except (OSError, ValueError):
            return   # soubor se prave zapisuje - zkusime pozdeji
        self._mtime = m
        if isinstance(data, dict):
            old = bool(self.s.get("autostart"))
            self.s.update(data)
            if bool(self.s.get("autostart")) != old:
                try:
                    set_autostart(bool(self.s.get("autostart")))
                except Exception:
                    pass
            self.refresh()

    # --- stavba okna ---
    def build(self):
        r = self.root
        head = tk.Frame(r, bg=BG)
        head.pack(fill="x", padx=24, pady=(24, 10))
        self.logo = tk.Label(head, bg=BG)
        self.logo.pack(side="left")
        tk.Label(head, text=APP, bg=BG, fg=FG, font=("Segoe UI", 20, "bold")).pack(side="left", padx=12)

        card = tk.Frame(r, bg=CARD)
        card.pack(fill="x", padx=24, pady=8)
        top = tk.Frame(card, bg=CARD)
        top.pack(fill="x", padx=18, pady=(16, 4))
        self.dot = tk.Canvas(top, width=16, height=16, bg=CARD, highlightthickness=0)
        self.dot.pack(side="left")
        self.dot_id = self.dot.create_oval(2, 2, 14, 14, fill=LEVELS["off"], outline="")
        self.status_lbl = tk.Label(top, text="", bg=CARD, fg=FG, font=("Segoe UI", 13, "bold"))
        self.status_lbl.pack(side="left", padx=8)
        self.phone_lbl = tk.Label(card, text="Telefon: nespárováno", bg=CARD, fg=MUTED,
                                  font=("Segoe UI", 10), anchor="w")
        self.phone_lbl.pack(fill="x", padx=18)
        self.stats_lbl = tk.Label(card, text=" ", bg=CARD, fg=MUTED, font=("Segoe UI", 10), anchor="w")
        self.stats_lbl.pack(fill="x", padx=18, pady=(0, 16))

        self.pair = tk.Frame(r, bg=CARD)
        self.pair_lbl = tk.Label(self.pair, text="", bg=CARD, fg=FG, font=("Segoe UI", 10),
                                 wraplength=330, justify="left")
        self.pair_lbl.pack(padx=18, pady=(14, 6), anchor="w")
        self.code = tk.Entry(self.pair, font=("Consolas", 24, "bold"), justify="center", bg=BG, fg=FG,
                             insertbackground=FG, relief="flat", width=10)
        self.code.pack(padx=18, pady=6, ipady=6)
        self.code.bind("<Return>", lambda e: self.submit_code())
        row = tk.Frame(self.pair, bg=CARD)
        row.pack(pady=(6, 14))
        self.ok_btn = Btn(row, "Spárovat", self.submit_code, "#888888")
        self.ok_btn.pack(side="left", padx=4)
        Btn(row, "Zrušit", self.cancel_code, BTN2).pack(side="left", padx=4)

        self.main_btn = Btn(r, "Vypnout", self.toggle, "#888888")
        self.main_btn.pack(fill="x", padx=24, pady=(16, 6))
        Btn(r, "Odpojit telefon", lambda: self.post("ask_unpair"), BTN2, fg=FG).pack(fill="x", padx=24, pady=6)
        Btn(r, "Nastavení", lambda: self.post("open_settings"), BTN2, fg=FG).pack(fill="x", padx=24, pady=6)
        self.auto_btn = Btn(r, "", self.toggle_auto, BTN2, fg=MUTED)
        self.auto_btn.pack(fill="x", padx=24, pady=6)

    def start_tray(self):
        try:
            import pystray
        except ImportError:
            return
        M, I, s = pystray.Menu, pystray.MenuItem, self.streamer
        menu = M(
            I("Otevřít ProxyLink", lambda: self.post("show"), default=True),
            I(lambda item: self.status or APP, lambda: None, enabled=False),
            M.SEPARATOR,
            I("Zapnout", lambda: s.set_enabled(True), visible=lambda item: not s.enabled.is_set()),
            I("Vypnout", lambda: s.set_enabled(False), visible=lambda item: s.enabled.is_set()),
            I("Odpojit telefon", lambda: self.post("ask_unpair")),
            I("Nastavení", lambda: self.post("open_settings")),
            I("Spouštět s Windows", lambda: self.post("toggle_auto"),
              checked=lambda item: bool(self.s.get("autostart"))),
            M.SEPARATOR,
            I("Konec", lambda: self.post("quit")),
        )
        self.tray = pystray.Icon(APP, make_icon(LEVELS["off"]), APP, menu)
        self.tray.run_detached()

    # --- vzhled podle stavu ---
    def color(self):
        return accent(self.s.get("phone_name") or APP)

    def refresh(self):
        acc = self.color()
        on = self.streamer.enabled.is_set()
        self.status_lbl.config(text=self.status)
        self.dot.itemconfig(self.dot_id, fill=LEVELS[self.level])
        self.main_btn.config(text="Vypnout" if on else "Zapnout")
        self.main_btn.recolor(acc if on else shade(acc, 0.85))
        self.ok_btn.recolor(acc)
        self.auto_btn.config(text="Spouštět s Windows: " + ("ano" if self.s.get("autostart") else "ne"))
        name = self.s.get("phone_name")
        self.phone_lbl.config(text=f"Telefon: {name} ({self.s['phone_ip']})" if name else "Telefon: nespárováno")
        try:
            from PIL import ImageTk
            self._logo = ImageTk.PhotoImage(make_icon(acc if on else LEVELS["off"], size=44))
            self.logo.config(image=self._logo)
            self.root.iconphoto(True, self._logo)
        except Exception:
            pass
        if self.tray:
            self.tray.icon = make_icon(acc if on else LEVELS["off"], LEVELS[self.level])
            self.tray.title = f"{APP} - {self.status}"
            self.tray.update_menu()

    # --- udalosti z vlakna / listy ---
    def poll(self):
        try:
            while True:
                kind, data = self.q.get_nowait()
                getattr(self, "on_" + kind)(**data)
        except queue.Empty:
            pass
        self._tick += 1
        if self._tick % 20 == 0:
            self.check_settings()
        self.root.after(100, self.poll)

    def on_state(self, text, level):
        self.status, self.level = text, level
        if level != "ok":
            self.stats_lbl.config(text=" ")
        self.refresh()

    def on_enabled(self, value):
        if not value:
            self.pair.pack_forget()
        self.refresh()

    def on_phone(self, name, ip):
        self.refresh()

    def on_stats(self, kbps, uptime, dropped, playing):
        if self.level != "ok":
            return
        txt = f"{kbps:.0f} kbit/s  ·  {fmt_time(uptime)}"
        txt += "" if playing else "  ·  ticho"
        txt += f"  ·  zahozeno {dropped}" if dropped else ""
        self.stats_lbl.config(text=txt)

    def on_need_code(self, name):
        self.show()
        self.pair_lbl.config(text=f"{name or 'Telefon'} žádá o spárování.\n"
                                  "Zadej jednorázový kód, který se zobrazil na telefonu:")
        self.code.delete(0, "end")
        self.pair.pack(fill="x", padx=24, pady=8, before=self.main_btn)
        self.code.focus_set()

    def on_show(self):
        self.show()

    def on_ask_unpair(self):
        if messagebox.askyesno(APP, "Odpojit telefon a zapomenout párování?\n"
                                    "Příště bude potřeba nový jednorázový kód."):
            self.streamer.unpair()

    def on_open_settings(self):
        self.save()   # zajisti, ze soubor existuje
        try:
            subprocess.Popen(["notepad", CONFIG])
        except OSError as e:
            messagebox.showerror(APP, f"Nepodařilo se otevřít nastavení:\n{e}")

    def on_toggle_auto(self):
        self.toggle_auto()

    def on_quit(self):
        self.streamer.stop()
        if self.tray:
            self.tray.stop()
        self.root.destroy()

    # --- akce ---
    def toggle(self):
        self.streamer.set_enabled(not self.streamer.enabled.is_set())

    def toggle_auto(self):
        self.s["autostart"] = not self.s.get("autostart")
        self.save()
        try:
            set_autostart(self.s["autostart"])
        except Exception as e:
            messagebox.showerror(APP, f"Nepodařilo se změnit automatický start:\n{e}")
        self.refresh()

    def submit_code(self):
        code = self.code.get().strip()
        if code:
            self.streamer.code_q.put(code)
            self.pair.pack_forget()

    def cancel_code(self):
        self.streamer.code_q.put("")
        self.pair.pack_forget()

    def show(self):
        self.root.deiconify()
        self.root.lift()
        self.root.focus_force()

    def hide(self):
        if self.tray:
            self.root.withdraw()
        else:
            self.on_quit()

    def run(self):
        self.root.mainloop()


# ---------- start ----------

def single_instance(show=True):
    s = socket.socket()
    try:
        s.bind(("127.0.0.1", LOCK_PORT))
        s.listen(1)
    except OSError:
        if show:   # uz bezi - rekni mu, ať ukáže okno
            try:
                c = socket.create_connection(("127.0.0.1", LOCK_PORT), timeout=1)
                c.sendall(b"show")
                c.close()
            except OSError:
                pass
        return None
    return s


def main():
    ap = argparse.ArgumentParser(description=APP)
    ap.add_argument("--background", action="store_true", help="start jen v systemove liste")
    ap.add_argument("--write-settings", action="store_true", help="vytvori settings.json a skonci")
    ap.add_argument("--list", action="store_true", help="vypise zdroje zvuku a skonci")
    args = ap.parse_args()

    if args.list:
        p = pyaudio.PyAudio()
        devs, di = list_devices(p)
        for i, d in enumerate(devs):
            print(f"{i + 1}. {d['name']} [{int(d['defaultSampleRate'])} Hz]{'  (vychozi)' if i == di else ''}")
        p.terminate()
        return

    if args.write_settings:
        make_saver(load_settings())()
        print(CONFIG)
        return

    lock = single_instance(show=not args.background)
    if lock is None:
        print(f"{APP} uz bezi.")
        return
    try:
        import ctypes
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        pass

    try:   # vyssi priorita procesu a jemnejsi casovac = mensi zpozdeni
        import ctypes
        k32 = ctypes.windll.kernel32
        k32.SetPriorityClass(k32.GetCurrentProcess(), 0x00008000)
        ctypes.windll.winmm.timeBeginPeriod(1)
    except Exception:
        pass

    settings = load_settings()
    try:
        set_autostart(bool(settings.get("autostart")))
    except Exception:
        pass
    App(settings, args.background, lock).run()


if __name__ == "__main__":
    main()
