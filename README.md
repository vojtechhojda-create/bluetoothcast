# proxylink

proxylink is a high-performance, secure local bridge connecting Windows PC and Android devices over Wi-Fi/LAN with ultra-low latency.

---

## v1.0 — MVP (Minimum Viable Product)
*Goal: Establish core P2P connection, secure pairing, and basic audio/control streaming.*

- [ ] **Core Networking & Discovery**
  - [ ] mDNS local discovery (`_proxylink._tcp.local.`)
  - [ ] Secure WebSocket server on Windows backend (Node.js/TypeScript)
  - [ x ] Android client connection handling
- [ ] **Secure Pairing (QR Code)**
  - [ ] QR code generation on PC with one-time pairing token
  - [ ] Android camera QR scanner & pairing request flow
  - [ ] Windows tray notification whitelist approval dialog
- [ ] **Audio Streaming**
  - [ ] Windows system-wide audio capture via WASAPI Loopback
  - [ ] Real-time audio transmission and playback on Android (`AudioTrack`)
- [ ] **Remote Control & System Integration**
  - [ ] Media control (Play/Pause, Volume Up/Down/Mute)
  - [ ] Android Quick Settings Tile (connection toggle & status indicator)
  - [ ] Android Share Sheet integration (send text/links/files directly to PC)
- [ ] **Cross-Platform UI**
  - [ ] Windows System Tray Menu (status, QR code popup, disconnect)
  - [ ] Android Jetpack Compose main dashboard UI

---

## v1.1 — Reliability & Advanced Sharing
*Goal: Make the connection bulletproof and enhance productivity features.*

- [ ] **Auto-Reconnect & State Recovery**
  - [ ] Silent auto-pair on startup using stored cryptographic keys (Ed25519)
  - [ ] Network switch and sleep/wake recovery handling
- [ ] **Clipboard & File Bridge**
  - [ ] Manual Clipboard sync (Send text to PC / Copy from PC)
  - [ ] File transfer signal protocol (`file.offer`, `file.accept`, chunked transfer)
- [ ] **Developer Test Channel**
  - [ ] Built-in Ping/Pong latency measurement
  - [ ] Echo test and local debug event logs

---

## v1.2 — Ecosystem & Advanced Routing
*Goal: App-specific audio routing and developer integrations.*

- [ ] **Per-App Audio Routing**
  - [ ] Application Loopback API support on Windows (stream only Spotify, Chrome, or game audio)
- [ ] **Bluetooth Relay & Dual Output**
  - [ ] Android acting as a Bluetooth audio bridge for PCs lacking Bluetooth hardware
- [ ] **TwoCode Integration**
  - [ ] Dedicated developer protocol channel for TwoCode workspace synchronization
