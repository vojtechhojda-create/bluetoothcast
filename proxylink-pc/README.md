# proxylink-pc

windows daemon + tray app + zen/firefox extension.

## install
requirements: node 20+, windows 10/11, .net 8 sdk (only for the audio helper).

1. `install.bat` (installs, builds, asks about autostart and starts the app)
2. `start.bat` starts it hidden in the background (`start-console.bat` shows a console for debugging)

the app lives in the tray: bottom right, click the arrow up. the icon is green. drag it to the taskbar to keep it always visible.
log: `%APPDATA%\proxylink\log.txt`.

## tray menu
pair new device (qr), send clipboard, send file, open received folder, open received files, open links from phone, start with windows, disconnect phones, forget all devices, quit.

"start with windows" writes `HKCU\...\Run\proxylink` (wscript + `scripts\start-hidden.vbs`). cli: `node dist\autostart-cli.js on|off`.

## pairing and security
- phone port 8787 is wss with a self-signed certificate (`%APPDATA%\proxylink\cert.pem`); the qr carries its sha-256 fingerprint and the phone pins it.
- first pairing: one-time token (10 min, 5 tries) + approval dialog. the phone stores an ec p-256 key in android keystore, the pc stores the public key; later reconnects are silent (challenge/signature).
- extension port 8788 binds 127.0.0.1 only and accepts only `moz-extension://` / `chrome-extension://` origins.
- files from phone are sanitized and saved to `%USERPROFILE%\Downloads\proxylink` (change `downloaddir` in `config.json`). with "open received files" on, a single safe file (images, pdf, text, audio, video, office) opens in its default app; anything else, or several files at once, opens the folder with the file selected, so nothing executable runs automatically; links from phone open only as http/https.
- env: `PROXYLINK_NOHOST=1` (qr without ip, mdns only), `PROXYLINK_LOOPBACK=<path to loopback.exe>`.

## zen extension (`extension-zen`)
temporary: `about:debugging#/runtime/this-firefox` -> load temporary add-on -> `extension-zen\manifest.json`.

permanent: `npm run ext:sign` with `WEB_EXT_API_KEY` / `WEB_EXT_API_SECRET` from addons.mozilla.org, then install the .xpi from `web-ext-artifacts` via `about:addons`.

if it says the pc app is not running: start it (`start.bat`), check the tray icon and `log.txt`.
