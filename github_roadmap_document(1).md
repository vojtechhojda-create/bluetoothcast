# proxylink roadmap

connect your pc and mobile together smart way.

proxylink is a high-performance, secure local bridge connecting windows pc and android devices over wi-fi/lan with ultra-low latency. built with zero cloud dependency and a clean, developer-focused architecture.

---

## v1.0 — mvp (minimum viable product)

> goal: establish core p2p connection, secure pairing, and basic audio/control streaming.

- [ ] **core networking & discovery**
  - [ ] mdns local discovery (`_proxylink._tcp.local.`)
  - [ ] secure websocket server on windows backend (node.js/typescript)
  - [ ] android client connection handling
- [ ] **secure pairing (qr code)**
  - [ ] qr code generation on pc with one-time pairing token
  - [ ] android camera qr scanner & pairing request flow
  - [ ] windows tray notification whitelist approval dialog
- [ ] **audio streaming**
  - [ ] windows system-wide audio capture via wasapi loopback
  - [ ] real-time audio transmission and playback on android (`audiotrack`)
- [ ] **remote control & system integration**
  - [ ] media control (play/pause, volume up/down/mute)
  - [ ] android quick settings tile (connection toggle & status indicator)
  - [ ] android share sheet integration (send text/links/files directly to pc)
- [ ] **cross-platform ui**
  - [ ] windows system tray menu (status, qr code popup, disconnect)
  - [ ] android jetpack compose main dashboard ui

---

## v1.1 — reliability & advanced sharing

> goal: make the connection bulletproof and enhance productivity features.

- [ ] **auto-reconnect & state recovery**
  - [ ] silent auto-pair on startup using stored cryptographic keys (ed25519)
  - [ ] network switch and sleep/wake recovery handling
- [ ] **clipboard & file bridge**
  - [ ] manual clipboard sync (send text to pc / copy from pc)
  - [ ] file transfer signal protocol (`file.offer`, `file.accept`, chunked transfer)
- [ ] **developer test channel**
  - [ ] built-in ping/pong latency measurement
  - [ ] echo test and local debug event logs

---

## v1.2 — ecosystem & advanced routing

> goal: app-specific audio routing and developer integrations.

- [ ] **per-app audio routing**
  - [ ] application loopback api support on windows (stream only spotify, chrome, or game audio)
- [ ] **bluetooth relay & dual output**
  - [ ] android acting as a bluetooth audio bridge for pcs lacking bluetooth hardware
- [ ] **twocode integration**
  - [ ] dedicated developer protocol channel for twocode workspace synchronization