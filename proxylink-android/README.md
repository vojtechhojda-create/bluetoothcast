# proxylink-android

open in android studio (ladybug+), sync, run. agp 8.7.3, kotlin 2.0.21, minsdk 26.

## protocol (wss://host:8787, certificate pinned by sha-256 fingerprint from the qr)
text (json):
- phone -> pc: auth.pair {token, deviceid, devicename, pubkey}  (first pairing, pubkey = keystore ec p-256, spki base64)
- phone -> pc: auth.hello {deviceid} -> pc: auth.challenge {nonce} -> phone: auth.resume {sig}  (silent reconnect, sha256withecdsa)
- pc -> phone: auth.pair.ok {name} | auth.pair.pending | auth.pair.error/denied {code}
- phone -> pc: audio.start | audio.stop ; pc -> phone: audio.config {rate, channels} (pcm s16le)
- phone -> pc: media.control {action: play_pause|vol_up|vol_down|mute}
- phone -> pc: share.send {kind: text|link, data}
- pc -> phone: share.push {kind: text|link, data, title}
- both ways: file.offer {id, name, size} ... file.done {id}
- ping {t} -> pong {t}

binary (first byte = frame type):
- 0x01 pc -> phone: pcm audio
- 0x02 phone -> pc: [0x02][id int32 be][file chunk]
- 0x03 pc -> phone: [0x03][id int32 be][file chunk]

qr payload: {name, port, token, fp, host?}  (host tried first, mdns fallback)
close codes: 4401/4403 auth failure, 4408 disconnected by pc (no auto-reconnect)
received files: downloads/proxylink
