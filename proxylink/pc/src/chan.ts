import { EventEmitter } from "node:events";

export interface chanmsg {
  ch: string;
  from: string;
  name: string;
  data: unknown;
}

export const chanre = /^[a-z0-9._-]{1,32}$/;
export const chanbus = new EventEmitter();
