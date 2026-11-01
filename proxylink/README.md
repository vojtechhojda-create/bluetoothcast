# proxylink

proxylink is a high-performance, secure local bridge connecting Windows PC and Android devices over Wi-Fi/LAN with ultra-low latency.

---

## v1.0 — MVP (Minimum Viable Product)
*Goal: Establish core P2P connection, secure pairing, and basic audio/control streaming.*

- [x] **Core Networking & Discovery**
  - [x] mDNS local discovery (`_proxylink._tcp.local.`)
  - [x] Secure WebSocket server on Windows backend (Node.js/TypeScript) — wss, self-signed cert pinned via qr fingerprint
  - [x] Android client connection handling
- [x] **Secure Pairing (QR Code)**
  - [x] QR code generation on PC with one-time pairing token
  - [x] Android camera QR scanner & pairing request flow
  - [x] Windows approval dialog (whitelist)
- [x] **Audio Streaming**
  - [x] Windows system-wide audio capture via WASAPI Loopback
  - [x] Real-time audio transmission and playback on Android (`AudioTrack`)
- [x] **Remote Control & System Integration**
  - [x] Media control (Play/Pause, Volume Up/Down/Mute)
  - [x] Android Quick Settings Tile (connection toggle & status indicator)
  - [x] Android Share Sheet integration (send text/links/files directly to PC)
- [x] **Cross-Platform UI**
  - [x] Windows System Tray Menu (status, QR code popup, disconnect)
  - [x] Android Jetpack Compose main dashboard UI

---

## v1.1 — Reliability & Advanced Sharing
*Goal: Make the connection bulletproof and enhance productivity features.*

- [x] **Auto-Reconnect & State Recovery**
  - [x] Silent auto-pair on startup using stored cryptographic keys (ECDSA P-256 in Android Keystore, Ed25519 is not available in Keystore below API 33)
  - [x] Network switch and sleep/wake recovery handling (retries for about 80 s)
- [ ] **Clipboard & File Bridge**
  - [x] Manual Clipboard sync (Send text to PC / Copy from PC)
  - [ ] File transfer signal protocol (`file.offer`, `file.accept`, chunked transfer) — offer/chunks/done work, accept handshake missing
- [x] **Developer Test Channel**
  - [x] Built-in Ping/Pong latency measurement (phone: dev tools)
  - [x] Echo test and local debug event logs

---

## v1.2 — Ecosystem & Advanced Routing
*Goal: App-specific audio routing and developer integrations.*

- [x] **Per-App Audio Routing**
  - [x] Application Loopback API support on Windows (stream only Spotify, Chrome, or game audio) — needs windows 10 20348+ / 11, untested on real hardware
- [x] **Bluetooth Relay & Dual Output**
  - [x] Android acting as a Bluetooth audio bridge for PCs lacking Bluetooth hardware (output chooser + buffer, pc keeps playing)
- [ ] **TwoCode Integration**
  - [x] Generic developer channel (`ws://127.0.0.1:8788/dev`, channels, token)
  - [ ] TwoCode workspace sync adapter — needs TwoCode's message format

---

## Extras (not in the plan)
- [x] Zen/Firefox extension (send page, link, image, selection to phone)
- [x] PC -> phone push (links, text, files) with notifications
- [x] Start with Windows (tray toggle), runs hidden in the tray
- [x] PC panel (window): audio both directions, volume, send text/links/files, settings, log
- [x] Phone audio / microphone to PC
- [x] Android UI redesign (icon colors, tabs)
