"""
BluetoothCast - odesilac zvuku z PC do telefonu.

Instalace:  pip install pyaudiowpatch numpy

Pouziti:
    python pc_audio_sender.py                  (nastaveni se bere ze settings.json)
    python pc_audio_sender.py --ip 10.0.0.4    (IP telefonu rucne, jen pro tento beh)
    python pc_audio_sender.py --list           (vypise zarizeni a skonci)
    python pc_audio_sender.py --device 2       (vybere zarizeni cislo 2)
    python pc_audio_sender.py --pin 123456     (PIN z aplikace, povoleni bez klepani na telefonu)
    python pc_audio_sender.py --config cesta   (jiny soubor s nastavenim)

Telefon ukaze notifikaci "Povolit / Zamitnout". Ukonceni: Ctrl+C.
"""
import argparse
import json
import os
import queue
import socket
import sys
import threading
import time

import numpy as np
import pyaudiowpatch as pyaudio

PORT = 8766
HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_CONFIG = os.path.join(HERE, "settings.json")

DEFAULTS = {
    "phone_ip": "",          # IP telefonu; prazdne = hledat automaticky
    "pin": "",               # PIN z aplikace (pro automaticke povoleni), v uvozovkach, napr. "123456"
    "discovery": True,       # kdyz IP nefunguje, zkusit telefon najit v siti
    "device": "",            # nazev zdroje zvuku (staci cast nazvu); prazdne = zeptat se / vychozi
    "auto_connect": True,    # True = nic se neptat, pripojovat dokud se to nepovede
    "reconnect": True,       # po vypadku spojeni se znovu pripojit
    "retry_delay_s": 3,      # pauza mezi pokusy (sekundy)
    "max_retries": 0,        # 0 = bez omezeni
    "on_denied": "exit",     # "exit" = skoncit, "retry" = zkouset znovu i po zamitnuti
    "remember_choices": True,  # ulozit nalezenou IP a vybrane zarizeni do souboru
    "chunk_ms": 20,          # velikost bloku zvuku (mensi = mensi zpozdeni)
    "queue_size": 25,        # kolik bloku se muze nahromadit pred zahazovanim
}


# ---------- nastaveni ----------

def save_settings(path, settings):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(settings, f, indent=2, ensure_ascii=False)
        f.write("\n")


def load_settings(path):
    """Vrati (nastaveni, smi_se_prepisovat)."""
    s = dict(DEFAULTS)
    if not os.path.exists(path):
        try:
            save_settings(path, s)
            print(f"Vytvoren soubor s nastavenim: {path}")
        except OSError:
            pass
        return s, True
    try:
        with open(path, encoding="utf-8-sig") as f:
            data = json.load(f)
        if not isinstance(data, dict):
            raise ValueError("ocekavam objekt { ... }")
        s.update(data)
        return s, True
    except (OSError, ValueError) as e:
        print(f"Nastaveni se nepodarilo nacist ({e}). Pouzivam vychozi a soubor neprepisuji.")
        return s, False


def num(settings, key, minimum=0):
    try:
        v = int(settings[key])
    except (TypeError, ValueError):
        v = DEFAULTS[key]
    return max(v, minimum)


# ---------- sit ----------

def discover(tries=3, timeout=1.5):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.settimeout(timeout)
    try:
        for _ in range(tries):
            try:
                s.sendto(b"CMF_AUDIO?", ("255.255.255.255", PORT))
                data, addr = s.recvfrom(64)
                if data == b"CMF_AUDIO!":
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


def find_phone(explicit_ip, settings):
    """Vrati (socket, ip) nebo (None, None)."""
    ip = explicit_ip or str(settings.get("phone_ip") or "").strip()
    if ip:
        s = try_connect(ip)
        if s:
            return s, ip
    if explicit_ip:
        return None, None  # IP zadana rucne - nehledat dal
    if settings.get("discovery"):
        found = discover()
        if found and found != ip:
            s = try_connect(found)
            if s:
                return s, found
    return None, None


def recv_line(sock):
    buf = b""
    while True:
        c = sock.recv(1)
        if not c or c == b"\n":
            return buf.decode(errors="ignore").strip()
        buf += c


# ---------- zarizeni ----------

def list_devices(p):
    """Vrati (seznam loopback zarizeni, index vychoziho)."""
    wasapi = p.get_host_api_info_by_type(pyaudio.paWASAPI)
    default_out = p.get_device_info_by_index(wasapi["defaultOutputDevice"])
    devs = list(p.get_loopback_device_info_generator())
    default_idx = 0
    for i, d in enumerate(devs):
        if default_out["name"] in d["name"]:
            default_idx = i
            break
    return devs, default_idx


def print_devices(devs, default_idx):
    print("Dostupna zarizeni (zdroje zvuku):")
    for i, d in enumerate(devs):
        mark = "  (vychozi)" if i == default_idx else ""
        print(f"  {i + 1}. {d['name']}  [{int(d['defaultSampleRate'])} Hz, "
              f"{d['maxInputChannels']} kanalu]{mark}")


def choose_device(p, settings, cli_number):
    devs, default_idx = list_devices(p)
    if not devs:
        raise RuntimeError("Nenasel jsem zadne zarizeni pro zachytavani zvuku.")

    if cli_number is not None:
        if not 1 <= cli_number <= len(devs):
            raise RuntimeError(f"Zarizeni {cli_number} neexistuje (1-{len(devs)}). Pouzij --list.")
        return devs[cli_number - 1]

    wanted = str(settings.get("device") or "").strip().lower()
    if wanted:
        for d in devs:
            if wanted in d["name"].lower():
                return d
        print(f"Zarizeni '{settings['device']}' ze settings.json nenalezeno.")

    if len(devs) == 1 or settings.get("auto_connect"):
        return devs[default_idx]

    print_devices(devs, default_idx)
    while True:
        try:
            raw = input(f"Vyber zarizeni [Enter = {default_idx + 1}]: ").strip()
        except EOFError:
            return devs[default_idx]
        if raw == "":
            return devs[default_idx]
        if raw.isdigit() and 1 <= int(raw) <= len(devs):
            return devs[int(raw) - 1]
        print("Neplatna volba.")


# ---------- vypis ----------

def fmt_time(sec):
    return time.strftime("%H:%M:%S", time.gmtime(sec))


def status_line(msg):
    sys.stdout.write("\r" + msg.ljust(90))
    sys.stdout.flush()


def end_status_line():
    sys.stdout.write("\n")
    sys.stdout.flush()


# ---------- jedna relace ----------

def run_session(p, dev, args, settings, config_path, writable):
    """Vraci: no_phone | denied | disconnected | interrupted."""
    rate = int(dev["defaultSampleRate"])
    in_ch = dev["maxInputChannels"]
    chunk_ms = num(settings, "chunk_ms", 5)
    queue_size = num(settings, "queue_size", 2)

    sock, ip = find_phone(args.ip, settings)
    if not sock:
        return "no_phone"

    try:
        hello = {"name": socket.gethostname(), "rate": rate, "channels": 2}
        pin = str(args.pin or settings.get("pin") or "").strip()
        if pin:
            hello["pin"] = pin
        sock.sendall((json.dumps(hello) + "\n").encode())
        end_status_line()
        print(f"Telefon nalezen: {ip}. Cekam na souhlas na telefonu...")
        sock.settimeout(30)
        answer = recv_line(sock)
    except OSError:
        sock.close()
        return "no_phone"

    if answer != "OK":
        sock.close()
        return "denied"
    sock.settimeout(None)

    if settings.get("remember_choices") and writable:
        changed = False
        if settings.get("phone_ip") != ip:
            settings["phone_ip"] = ip
            changed = True
        if settings.get("device") != dev["name"]:
            settings["device"] = dev["name"]
            changed = True
        if changed:
            try:
                save_settings(config_path, settings)
                print("Nastaveni ulozeno do settings.json.")
            except OSError:
                pass

    print()
    print(f"  Zdroj:    {dev['name']}")
    print(f"  Format:   {rate} Hz, 16 bit, stereo")
    print(f"  Telefon:  {ip}")
    print("  Konec:    Ctrl+C")
    print()

    q = queue.Queue(maxsize=queue_size)
    stop = threading.Event()
    stats = {"bytes": 0, "dropped": 0, "last": 0.0}

    def callback(in_data, frame_count, time_info, status):
        arr = np.frombuffer(in_data, dtype=np.int16)
        if in_ch != 2:
            arr = arr.reshape(-1, in_ch)
            arr = np.repeat(arr, 2, axis=1) if in_ch == 1 else arr[:, :2]
            arr = np.ascontiguousarray(arr).reshape(-1)
        stats["last"] = time.time()
        try:
            q.put_nowait(arr.tobytes())
        except queue.Full:
            stats["dropped"] += 1  # radeji zahodit nez zvysovat zpozdeni
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

    stream = None
    result = "disconnected"
    try:
        stream = p.open(
            format=pyaudio.paInt16,
            channels=in_ch,
            rate=rate,
            input=True,
            input_device_index=dev["index"],
            frames_per_buffer=rate * chunk_ms // 1000,
            stream_callback=callback,
        )
        start = time.time()
        last_t, last_bytes = start, 0
        while not stop.is_set():
            time.sleep(1.0)
            now = time.time()
            kbps = (stats["bytes"] - last_bytes) * 8 / 1000 / max(now - last_t, 0.001)
            last_t, last_bytes = now, stats["bytes"]
            state = "streamuje" if now - stats["last"] < 1.0 else "ticho (nic nehraje)"
            status_line(f"  [{state}]  {fmt_time(now - start)}  |  {kbps:6.0f} kbit/s  |  "
                        f"zahozeno: {stats['dropped']}  |  fronta: {q.qsize()}/{q.maxsize}")
        end_status_line()
        print("Spojeni s telefonem bylo ukonceno.")
    except KeyboardInterrupt:
        end_status_line()
        result = "interrupted"
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
    return result


# ---------- hlavni smycka ----------

def main():
    ap = argparse.ArgumentParser(description="BluetoothCast - odesilac")
    ap.add_argument("--ip", help="IP telefonu (jen pro tento beh)")
    ap.add_argument("--device", type=int, help="cislo zarizeni (viz --list)")
    ap.add_argument("--pin", help="PIN z aplikace (automaticke povoleni)")
    ap.add_argument("--list", action="store_true", help="vypise zarizeni a skonci")
    ap.add_argument("--config", default=DEFAULT_CONFIG, help="cesta k settings.json")
    args = ap.parse_args()

    p = pyaudio.PyAudio()

    if args.list:
        devs, default_idx = list_devices(p)
        print_devices(devs, default_idx)
        p.terminate()
        return

    settings, writable = load_settings(args.config)
    auto = bool(settings.get("auto_connect"))
    reconnect = bool(settings.get("reconnect"))
    delay = num(settings, "retry_delay_s", 1)
    max_retries = num(settings, "max_retries", 0)

    try:
        dev = choose_device(p, settings, args.device)
    except RuntimeError as e:
        print(e)
        p.terminate()
        return

    attempts = 0
    try:
        while True:
            if attempts == 0:
                print(f"Hledam telefon... (zdroj: {dev['name']})")
            result = run_session(p, dev, args, settings, args.config, writable)

            if result == "interrupted":
                break
            if result == "denied":
                print("Telefon zadost zamitl (nebo vyprsel cas).")
                if settings.get("on_denied") != "retry":
                    break
            elif result == "disconnected":
                attempts = 0
                if not reconnect:
                    break
            elif result == "no_phone":
                if not auto:
                    end_status_line()
                    print("Telefon jsem nenasel. Zapni sluzbu v aplikaci, zkontroluj stejnou Wi-Fi "
                          "a pripadne pouzij --ip <IP z notifikace>.")
                    break

            attempts += 1
            if max_retries and attempts > max_retries:
                end_status_line()
                print(f"Vzdavam to po {max_retries} pokusech.")
                break
            for left in range(delay, 0, -1):
                status_line(f"  Telefon nedostupny, dalsi pokus za {left} s (pokus {attempts})...")
                time.sleep(1)
            status_line("  Zkousim se pripojit...")
    except KeyboardInterrupt:
        end_status_line()
    finally:
        print("Ukoncuji.")
        p.terminate()


if __name__ == "__main__":
    main()
