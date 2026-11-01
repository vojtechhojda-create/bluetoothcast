# proxylink-android

open in android studio (ladybug+), sync, run. agp 8.7.3, kotlin 2.0.21, minsdk 26.

## protocol (wss://host:8787, certificate pinned by sha-256 fingerprint from the qr)
text (json):
- phone -> pc: auth.pair {token, deviceid, devicename, pubkey}  (first pairing, pubkey = keystore ec p-256, spki base64)
- phone -> pc: auth.hello {deviceid} -> pc: auth.challenge {nonce} -> phone: auth.resume {sig}  (silent reconnect, sha256withecdsa)
- pc -> phone: auth.pair.ok {name} | auth.pair.pending | auth.pair.error/denied {code}
- phone -> pc: audio.start {pid?} (pid = one app, omitted = all pc audio) | audio.stop ; pc -> phone: audio.config {rate, channels} (pcm s16le)
- phone -> pc: media.control {action: play_pause|vol_up|vol_down|mute}
- phone -> pc: share.send {kind: text|link, data}
- pc -> phone: share.push {kind: text|link, data, title}
- both ways: file.offer {id, name, size} ... file.done {id}
- ping {t} -> pong {t}
- phone -> pc: audio.up.start {rate, channels, source: mic|phone} | audio.up.stop ; pc -> phone: audio.up.stopped (pc side ended it)
- pc -> phone: audio.stopped (pc stopped the stream started for this phone)
- phone -> pc: apps.list -> pc: apps.list {apps: [{pid, name, title, active}]}
- both ways: chan.msg {ch, data} (dev channel, ch = a-z 0-9 . _ -, `echo` is answered by the pc)
- pc -> phone: error {code: audio_failed | audio_unavailable | unknown_action | unknown_type}

binary (first byte = frame type):
- 0x01 pc -> phone: pcm audio
- 0x02 phone -> pc: [0x02][id int32 be][file chunk]
- 0x03 pc -> phone: [0x03][id int32 be][file chunk]
- 0x04 phone -> pc: pcm audio (16 bit, rate/channels from audio.up.start)

qr payload: {name, port, token, fp, host?}  (host tried first, mdns fallback)
close codes: 4401/4403 auth failure, 4408 disconnected by pc (no auto-reconnect)
received files: downloads/proxylink

## twocode channel (chan.msg, ch = twocode)
phone -> pc: {op: workspaces} | {op: run, id, workspace, task} | {op: stop, id}
pc -> phone: {op: workspaces, items} | {op: run.start | run.out | run.done, id, ...}
