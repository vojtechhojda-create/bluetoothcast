"""
PC -> Android aplikace PC Audio (služba na pozadí).

Instalace:  pip install pyaudiowpatch numpy
Spuštění:   python pc_audio_sender.py            (telefon najde sám)
            python pc_audio_sender.py --ip 192.168.0.42

Telefon ukáže notifikaci "Povolit / Zamítnout". Po povolení se začne přehrávat.
Ukončení: Ctrl+C.
"""
import argparse
import json
import queue
import socket
import threading
import time

import numpy as np
import pyaudiowpatch as pyaudio

PORT = 8766
CHUNK_MS = 20


def discover(tries=3, timeout=1.5):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.settimeout(timeout)
    for _ in range(tries):
        s.sendto(b"CMF_AUDIO?", ("255.255.255.255", PORT))
        try:
            data, addr = s.recvfrom(64)
            if data == b"CMF_AUDIO!":
                return addr[0]
        except socket.timeout:
            pass
    return None


def recv_line(sock):
    buf = b""
    while True:
        c = sock.recv(1)
        if not c or c == b"\n":
            return buf.decode(errors="ignore").strip()
        buf += c


def get_loopback(p):
    wasapi = p.get_host_api_info_by_type(pyaudio.paWASAPI)
    spk = p.get_device_info_by_index(wasapi["defaultOutputDevice"])
    if not spk.get("isLoopbackDevice"):
        for lb in p.get_loopback_device_info_generator():
            if spk["name"] in lb["name"]:
                return lb
        raise RuntimeError("Nenašel jsem loopback zařízení pro výchozí výstup.")
    return spk


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ip", help="IP telefonu (jinak se hledá automaticky)")
    args = ap.parse_args()

    ip = args.ip or discover()
    if not ip:
        print("Telefon jsem nenašel. Zapni službu v aplikaci a zkus --ip <IP z notifikace>.")
        return
    print(f"Telefon: {ip}")

    p = pyaudio.PyAudio()
    dev = get_loopback(p)
    rate = int(dev["defaultSampleRate"])
    in_ch = dev["maxInputChannels"]

    sock = socket.create_connection((ip, PORT), timeout=5)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    hello = {"name": socket.gethostname(), "rate": rate, "channels": 2}
    sock.sendall((json.dumps(hello) + "\n").encode())

    print("Čekám na souhlas na telefonu...")
    sock.settimeout(30)
    if recv_line(sock) != "OK":
        print("Telefon žádost zamítl (nebo vypršel čas).")
        return
    sock.settimeout(None)
    print(f"Povoleno. Streamuju: {dev['name']} ({rate} Hz). Ctrl+C = konec.")

    q = queue.Queue(maxsize=25)
    stop = threading.Event()

    def callback(in_data, frame_count, time_info, status):
        arr = np.frombuffer(in_data, dtype=np.int16)
        if in_ch != 2:
            arr = arr.reshape(-1, in_ch)
            arr = np.repeat(arr, 2, axis=1) if in_ch == 1 else arr[:, :2]
            arr = np.ascontiguousarray(arr).reshape(-1)
        try:
            q.put_nowait(arr.tobytes())
        except queue.Full:
            pass  # raději zahodit než zvyšovat zpoždění
        return (None, pyaudio.paContinue)

    def sender():
        try:
            while not stop.is_set():
                try:
                    data = q.get(timeout=0.5)
                except queue.Empty:
                    continue
                sock.sendall(data)
        except OSError:
            pass
        finally:
            stop.set()

    threading.Thread(target=sender, daemon=True).start()

    stream = p.open(
        format=pyaudio.paInt16,
        channels=in_ch,
        rate=rate,
        input=True,
        input_device_index=dev["index"],
        frames_per_buffer=rate * CHUNK_MS // 1000,
        stream_callback=callback,
    )
    try:
        while not stop.is_set():
            time.sleep(0.2)
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        stream.stop_stream()
        stream.close()
        p.terminate()
        sock.close()
        print("Konec.")


if __name__ == "__main__":
    main()
