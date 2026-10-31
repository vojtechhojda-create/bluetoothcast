# proxylink-android

open in android studio (ladybug+), sync, run. agp 8.7.3, kotlin 2.0.21, minsdk 26.

## protocol (ws://host:8787)
text (json):
- phone -> pc: auth.pair {token, deviceid, devicename}
- pc -> phone: auth.pair.ok {name} | auth.pair.pending | auth.pair.error/denied {code}
- phone -> pc: audio.start | audio.stop
- pc -> phone: audio.config {rate, channels}   (pcm s16le)
- phone -> pc: media.control {action: play_pause|vol_up|vol_down|mute}
- phone -> pc: share.send {kind: text|link, data}
- phone -> pc: file.offer {id, name, size} ... file.done {id}

binary (first byte = frame type):
- 0x01 pc -> phone: pcm audio payload
- 0x02 phone -> pc: [0x02][id: int32 be][file chunk]

qr payload: {name, port, token, host?}  (host optional, skips mdns)
