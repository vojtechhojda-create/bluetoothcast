# proxylink roadmap

brief overview of planned releases for proxylink.

## v1.0 — mvp

> goal: core p2p connection, qr pairing, and audio streaming.

* [ ] networking & discovery: mdns discovery (`_proxylink._tcp.local.`), websocket server on pc (node.js), android client.
* [ ] qr pairing: single-use token (120s), camera scanner on mobile, approval prompt in windows tray.
* [ ] audio: wasapi loopback capture on windows, udp/websocket stream, playback via `audiotrack` on android.
* [ ] integration: android quick settings tile, share sheet (send links/text/files), media controls.
* [ ] ui: windows tray menu, android dashboard in jetpack compose.

## v1.1 — reliability and sharing

> goal: automatic reconnection and file/clipboard transfer.

* [ ] auto-pair: ed25519 key authentication for silent reconnects, tls 1.3 session encryption.
* [ ] clipboard & files: bidirectional clipboard sync, file transfer signal protocol (`file.offer`, `file.accept`).
* [ ] dev channel: ping/pong latency benchmarks, local test logs.

## v1.2 — routing and ecosystem

> goal: app-specific audio routing and developer environment integration.

* [ ] per-app audio: stream audio exclusively from selected windows applications (application loopback api).
* [ ] bluetooth relay: android acting as a bluetooth bridge for pc hardware.
* [ ] twocode bridge: dedicated ipc signal channel for twocode workspace integration.
* [ ] hardware dongle: technical specs for esp32-c3/s3 usb module for offline p2p transport.