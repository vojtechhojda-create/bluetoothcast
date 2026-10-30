# BluetoothCast

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
