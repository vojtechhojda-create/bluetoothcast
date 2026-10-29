# BluetoothCast

Streamuj zvuk z PC do telefonu s Androidem a nech ho přehrát na libovolném Bluetooth zařízení, které je k telefonu připojené. V počítači nepotřebuješ žádný Bluetooth adaptér.

[English](README.md)

## Proč to vzniklo

Můj stolní počítač nemá Bluetooth a nechtěl jsem kupovat dongle jen kvůli sluchátkům. Telefon Bluetooth umí, takže BluetoothCast z něj dělá most: PC zachytí, co zrovna hraje, pošle to přes Wi-Fi a telefon to přehraje do sluchátek, reproduktoru, autorádia nebo čehokoliv jiného, s čím je spárovaný.

Android část není plnohodnotná aplikace. Je to malá služba na pozadí s trvalou notifikací a přehrávání začne, až když na telefonu potvrdíš žádost.

## Jak to funguje

```
┌─────────────┐   Wi-Fi (raw PCM)   ┌──────────────┐   Bluetooth   ┌───────────────┐
│ Windows PC  │ ──────────────────► │ Android      │ ────────────► │ Sluchátka,    │
│ (odesílač)  │ ◄──── souhlas ───── │ (na pozadí)  │               │ reproduktor…  │
└─────────────┘                     └──────────────┘               └───────────────┘
```

1. Služba v telefonu poslouchá na portu 8766 a odpovídá na vyhledávací broadcast.
2. Skript na PC telefon najde, připojí se a požádá o povolení.
3. Telefon zobrazí notifikaci s tlačítky **Povolit** a **Zamítnout**.
4. Po povolení PC posílá nekomprimovaný zvuk v blocích po 20 ms a telefon ho přehrává přes nízkolatenční `AudioTrack`.

Když se síť zadrhne a nahromadí se zvuk, telefon nejstarší data zahodí a dožene aktuální stav, takže se zpoždění nezvětšuje.

## Požadavky

- Windows 10 nebo 11 s Pythonem 3.9+
- Android 8.0 (API 26) nebo novější
- PC a telefon ve stejné Wi-Fi síti

## Instalace

### Android aplikace

Android Studio nepotřebuješ. Repozitář obsahuje workflow pro GitHub Actions, které APK sestaví za tebe.

1. Forkni repozitář, nebo ho nahraj na vlastní účet.
2. Otevři záložku **Actions** a spusť **Build APK** (spouští se i po každém pushi).
3. Po dokončení stáhni artefakt `pcaudio-apk` a rozbal ho, uvnitř je `app-debug.apk`.
4. Nainstaluj APK do telefonu. Android se zeptá na povolení instalace z neznámých zdrojů.
5. Otevři aplikaci, povol notifikace a klepni na **Zapnout**.

Pokud to chceš sestavit lokálně, potřebuješ JDK 17 a Android SDK:

```
gradle assembleDebug
```

APK najdeš v `app/build/outputs/apk/debug/`.

### PC odesílač

```
pip install pyaudiowpatch numpy
python pc_audio_sender.py
```

Telefon se najde automaticky. Pokud router broadcast blokuje, zadej adresu z notifikace v telefonu:

```
python pc_audio_sender.py --ip 192.168.0.42
```

## Použití

1. Připoj Bluetooth zařízení k telefonu jako obvykle.
2. Zapni službu v aplikaci. Stačí to udělat jednou, pak běží na pozadí.
3. Spusť `pc_audio_sender.py` na PC.
4. V notifikaci na telefonu klepni na **Povolit**.
5. Zvuk z PC teď hraje v připojeném zařízení. Ukončíš ho pomocí `Ctrl+C` na PC.

## Řešení problémů

| Problém | Co zkusit |
|---|---|
| Telefon se nenajde | Některé routery izolují klienty nebo blokují broadcast. Použij `--ip`. |
| Služba se po čase zastaví | Vypni pro aplikaci optimalizaci baterie. Někteří výrobci (Xiaomi, Samsung a další) služby na pozadí agresivně ukončují. |
| Praskání nebo výpadky | Zvětši buffer (`bufBytes` v `AudioService.kt`), přibliž se k routeru nebo připoj PC kabelem. |
| Zvuk zaostává za obrazem | Počítej se zpožděním zhruba 100–200 ms celkem, podle Wi-Fi a Bluetooth zařízení. Pro hudbu a většinu her v pořádku, u videa to může být znát. |
| Dotaz firewallu ve Windows | Povol přístup v soukromých sítích. |
| Zvuk hraje i z reproduktorů PC | Ztlum je, nebo přepni výstup PC na tiché zařízení. |

## Protokol

Port `8766`, UDP i TCP.

- **Discovery (UDP):** PC pošle broadcast `CMF_AUDIO?` a telefon odpoví `CMF_AUDIO!`.
- **Handshake (TCP):** PC pošle jeden řádek JSON, např. `{"name": "muj-pc", "rate": 48000, "channels": 2}`, zakončený `\n`.
- **Souhlas:** telefon odpoví `OK\n` nebo `NO\n`. Nezodpovězená žádost po 20 sekundách vyprší.
- **Stream:** po `OK` PC posílá surové 16bitové PCM (little-endian, prokládané kanály).

## Bezpečnost

Port je otevřený v lokální síti, takže kdokoliv ve stejné Wi-Fi může poslat žádost o přehrávání. Bez tvého souhlasu se nikdy nic nepřehraje, ale samotné spojení není šifrované ani ověřené. Nepoužívej to ve veřejných ani nedůvěryhodných sítích.

## Omezení

- Odesílač funguje jen na Windows (využívá WASAPI loopback).
- Zvuk je nekomprimovaný, což drží nízké zpoždění, ale chce to rozumně stabilní Wi-Fi (zhruba 1,5 Mbit/s).
- Streamovat může najednou jen jeden počítač.
- APK z workflow je ladicí (debug) sestavení.

## Plány

- Párování pomocí PINu a „zapamatovat si toto PC“
- Šifrované spojení
- Komprese Opus pro slabší sítě
- Ikona v tray a výběr výstupního zařízení na PC
- Nastavitelná velikost bufferu v aplikaci

## Licence

MIT. Viz [LICENSE](LICENSE).
