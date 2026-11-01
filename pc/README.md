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

## v1.2
### per-app audio
the phone shows a source row: "all pc audio" or an app that currently has an audio session (spotify, chrome, a game; `*` = playing now). the loopback helper uses the windows application loopback api (`loopback.exe --pid <pid>`), which needs windows 10 build 20348+ or windows 11. `loopback.exe --list` prints the apps as json. if a per-app capture fails you get `audio_failed` on the phone and the reason on stderr of the helper (run `bin\loopback.exe --pid <pid>` in a console to see it).

### bluetooth relay and dual output
the pc keeps playing on its own speakers while the stream goes to the phone (dual output). the phone chooses where to play it: speaker, wired or bluetooth headphones (chips under "output on phone"). for bluetooth raise "buffer" (x4 to x6) if the sound crackles.

### dev channel (twocode and other tools)
generic message channel between the phone and local programs.

- endpoint: `ws://127.0.0.1:8788/dev`, token in `%APPDATA%\proxylink\dev-token.txt`
- first message: `{"type":"hello","token":"...","channels":["twocode"]}` -> `{"type":"hello.ok","pc":"...","devices":[{"id","name"}]}`
- phone -> program: `{"type":"chan.msg","ch":"twocode","from":"<deviceid>","name":"...","data":...}`
- program -> phone: `{"type":"chan.send","ch":"twocode","data":...,"to":"<deviceid>"}` (`to` optional) -> `{"type":"chan.result","ok":true,"count":1}`
- channel names: `a-z 0-9 . _ -`, up to 32 chars; `"*"` subscribes to all; `echo` is answered by the pc itself
- browsers (`http(s)` origin) are refused

the phone app has "dev tools" (ping latency, echo test, event log).

## v1.3
### pc panel (the pc client)
tray menu > "open panel" opens a window (edge app mode, `panelapp: false` in `config.json` uses the default browser). it shows every connected phone and lets you:

- start / stop pc audio to a phone, pick "all pc audio" or one app, set the volume (0-200 %)
- see and stop the phone-to-pc audio stream, set its volume
- send text, links and files (drag and drop) to a phone
- toggle "open links" / "open received files", read the event log

the panel is served at `http://127.0.0.1:8788/panel`. it only works when opened from the tray: the tray creates a one-time ticket (60 s) that the page exchanges for a session. web pages cannot connect (origin and host checks).

### phone audio to pc
in the android app (audio tab > "this phone to pc"):

- "phone mic": microphone, 48 khz mono
- "phone audio": everything the phone plays (android 10+, asks for screen capture consent; apps that block capture stay silent; proxylink's own playback is excluded so there is no echo loop)

the pc plays it on the default output with `loopback.exe --play <rate> <channels>`. to use it as a microphone on the pc, set the default playback device to a virtual cable (for example vb-cable) and pick the cable's output as the microphone in your app.

## twocode bridge
the bridge connects the phone to your projects through the dev channel (`twocode`). it starts together with proxylink when `%APPDATA%\proxylink\twocode.json` exists (copy `twocode/twocode.example.json` there), or manually with `npm run twocode`.

- the phone (tools tab > twocode workspaces) lists your workspaces with git branch and number of changed files
- it runs the named tasks from the config and shows the live output; stop and a time limit (`timeoutsec`) are built in
- the phone can only pick a workspace id and a task name. it can never send a raw command, and the commands in `twocode.json` are yours
- only one task per workspace at a time, output is capped at 64 kb

messages (channel `twocode`, `data`):
- phone: `{op:"workspaces"}`, `{op:"run", id, workspace, task}`, `{op:"stop", id}`
- bridge: `{op:"workspaces", items:[{id,name,branch,dirty,tasks}]}`, `{op:"run.start"|"run.out"|"run.done", id, ...}`

this is independent of the twocode app itself. a native adapter (reading its real project/terminal state) needs its api: send me a link to the docs or repo.
