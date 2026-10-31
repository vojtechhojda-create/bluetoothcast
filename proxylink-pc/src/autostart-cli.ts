import { setautostart } from "./winsys.js";

const on = process.argv[2] !== "off";
await setautostart(on).catch((e) => console.error("failed:", (e as Error).message));
console.log(`start with windows: ${on ? "on" : "off"}`);
