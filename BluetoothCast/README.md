# BluetoothCast

Stream your PC's audio to an Android phone and let the phone play it on whatever Bluetooth device it's connected to. No Bluetooth adapter needed in the PC.

[Česky](README.cs.md)

## Why this exists

My desktop has no Bluetooth, and I didn't want to buy a dongle just to use a pair of earbuds. Phones already speak Bluetooth, so BluetoothCast uses the phone as the bridge: the PC captures what is playing, sends it over Wi-Fi, and the phone plays it out to headphones, a speaker, a car stereo or anything else it is paired with.

The Android side is not a full app. It is a small background service with a persistent notification, and it only starts playing after you approve a request on the phone.

## How it works

```
┌─────────────┐   Wi-Fi (raw PCM)   ┌──────────────┐   Bluetooth   ┌───────────────┐
│ Windows PC  │ ──────────────────► │ Android      │ ────────────► │ Headphones,   │
│ (sender)    │ ◄──── approval ──── │ (background) │               │ speaker, ...  │
└─────────────┘                     └──────────────┘               └───────────────┘
```

1. The service on the phone listens on port 8766 and answers discovery broadcasts.
2. The PC script finds the phone, connects and asks for permission.
3. The phone shows a notification with **Allow** and **Deny**.
4. Once allowed, the PC streams uncompressed audio in 20 ms blocks and the phone plays it through a low-latency `AudioTrack`.

If the network stalls and audio piles up, the phone drops the oldest data and catches up instead of letting the delay grow.

## Requirements

- Windows 10 or 11 with Python 3.9+
- Android 8.0 (API 26) or newer
- PC and phone on the same Wi-Fi network

## Installation

### Android app

You don't need Android Studio. The repository includes a GitHub Actions workflow that builds the APK.

1. Fork this repository, or push it to your own account.
2. Open the **Actions** tab and run **Build APK** (it also runs on every push).
3. When it finishes, download the `pcaudio-apk` artifact and unzip it to get `app-debug.apk`.
4. Install the APK on your phone. Android will ask you to allow installs from unknown sources.
5. Open the app, grant the notification permission and tap **Zapnout** (Start).

To build locally instead, you need JDK 17 and the Android SDK:

```
gradle assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/`.

### PC sender

```
pip install pyaudiowpatch numpy
python pc_audio_sender.py
```

The phone is discovered automatically. If your router blocks broadcasts, pass the address shown in the phone's notification:

```
python pc_audio_sender.py --ip 192.168.0.42
```

## Usage

1. Connect your Bluetooth device to the phone as usual.
2. Start the service in the app. You only need to do this once; it keeps running in the background.
3. Run `pc_audio_sender.py` on the PC.
4. Tap **Allow** in the notification on the phone.
5. Audio from the PC now plays on the connected device. Press `Ctrl+C` on the PC to stop.

## Troubleshooting

| Problem | What to try |
|---|---|
| Phone is not found | Some routers isolate clients or block broadcasts. Use `--ip`. |
| Service stops after a while | Disable battery optimization for the app. Some vendors (Xiaomi, Samsung and others) aggressively kill background services. |
| Crackling or dropouts | Increase the buffer (`bufBytes` in `AudioService.kt`), move closer to the router, or put the PC on Ethernet. |
| Audio lags behind video | Expect roughly 100–200 ms end to end, depending on Wi-Fi and the Bluetooth device. Fine for music and most games, noticeable in video. |
| Windows firewall prompt | Allow access on private networks. |
| Sound also plays on the PC speakers | Mute them, or switch the PC output to a silent device. |

## Protocol

Port `8766`, both UDP and TCP.

- **Discovery (UDP):** the PC broadcasts `CMF_AUDIO?` and the phone replies `CMF_AUDIO!`.
- **Handshake (TCP):** the PC sends one JSON line, for example `{"name": "my-pc", "rate": 48000, "channels": 2}`, terminated by `\n`.
- **Consent:** the phone answers `OK\n` or `NO\n`. Unanswered requests time out after 20 seconds.
- **Stream:** after `OK`, the PC sends raw 16-bit little-endian interleaved PCM.

## Security

The port is open on your local network, so anyone on the same Wi-Fi can send a playback request. Nothing is ever played without your approval, but the connection itself is not encrypted or authenticated. Don't use this on public or untrusted networks.

## Limitations

- The sender is Windows-only (it relies on WASAPI loopback capture).
- Audio is uncompressed, which keeps latency low but needs a reasonably stable Wi-Fi connection (about 1.5 Mbit/s).
- Only one PC can stream at a time.
- The released APK is a debug build.

## Roadmap

- PIN pairing and "remember this PC"
- Encrypted connection
- Opus compression for weaker networks
- Tray icon and output device selection on the PC
- Adjustable buffer size in the app

## License

MIT. See [LICENSE](LICENSE).
