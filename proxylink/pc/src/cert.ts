import { X509Certificate } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import selfsigned from "selfsigned";
import { datadir } from "./config.js";

const keyfile = join(datadir, "key.pem");
const certfile = join(datadir, "cert.pem");

if (!existsSync(keyfile) || !existsSync(certfile)) {
  const p = await selfsigned.generate([{ name: "commonName", value: "proxylink" }], {
    notAfterDate: new Date(Date.now() + 10 * 365 * 86_400_000),
    keySize: 2048,
  });
  writeFileSync(keyfile, p.private, { mode: 0o600 });
  writeFileSync(certfile, p.cert);
}

const key = readFileSync(keyfile, "utf8");
const cert = readFileSync(certfile, "utf8");

export const tls = {
  key,
  cert,
  fp: new X509Certificate(cert).fingerprint256.replace(/:/g, "").toLowerCase(),
};
