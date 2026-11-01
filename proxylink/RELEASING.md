# releasing

## one-time setup (github > repo > settings > secrets and variables > actions)

android signing (without it the release gets a debug-signed apk that cannot be updated over the previous one):

```
keytool -genkeypair -v -keystore release.jks -alias proxylink -keyalg RSA -keysize 2048 -validity 10000
```

powershell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Clipboard`

| secret | value |
|---|---|
| `KEYSTORE_BASE64` | the base64 text of `release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `proxylink` |
| `KEY_PASSWORD` | key password |

keep `release.jks` and its passwords somewhere safe (not in the repo). losing it means users have to uninstall before updating.

optional, signed zen extension (.xpi): `AMO_API_KEY`, `AMO_API_SECRET` from addons.mozilla.org > developer hub > api keys.

## a release

```
git add .
git commit -m "v1.4.0"
git push
git tag v1.4.0
git push origin v1.4.0
```

the `release` workflow builds everything and attaches it to the github release:

- `proxylink-<version>.apk`
- `proxylink-pc-<version>-windows.zip` (needs node 20 on the pc; start with `start.bat`)
- `proxylink-zen-extension-<version>.zip` (and the signed `.xpi` when the amo secrets are set)

you can also run it by hand: actions > release > run workflow (builds the files, no release is created).

## ci

`ci` runs on every push and pull request: typecheck of the pc app, extension lint, c# helper build and a debug android build. if one of them fails, open the failed step in the actions tab.
