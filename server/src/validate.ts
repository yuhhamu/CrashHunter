import type { Env, Item, ModRef, Report } from "./types";

const ID = /^[a-z0-9_.\-]{1,64}$/i;
const VER = /^[\w.+\-]{1,64}$/;
const SHA1 = /^[0-9a-f]{40}$/;
const HEX = /^[0-9a-f]{8,64}$/;
const MC = /^[0-9a-z.\-]{1,24}$/i;
const LOADERS = new Set(["forge", "neoforge", "fabric", "quilt", "forge_legacy", "vanilla", "unknown"]);
const INSTALL = /^[0-9a-f\-]{8,64}$/i;
const KIND = /^[A-Z_+]{1,120}$/;
const REASON = /^[A-Z_]{1,32}$/;
const EXNAME = /^[\w$.]{1,80}$/;

export class Invalid extends Error {}

function str(v: unknown): v is string { return typeof v === "string" }

function ref(v: any): ModRef {
  if (!v || !str(v.id) || !ID.test(v.id)) throw new Invalid("mod id");
  const out: ModRef = { id: v.id.toLowerCase() };
  if (v.v !== undefined && v.v !== null) { if (!str(v.v) || !VER.test(v.v)) throw new Invalid("mod version"); out.v = v.v }
  if (v.sha1 !== undefined && v.sha1 !== null) { if (!str(v.sha1) || !SHA1.test(v.sha1)) throw new Invalid("sha1"); out.sha1 = v.sha1 }
  if (v.cf !== undefined && v.cf !== null) { if (typeof v.cf !== "number" || !Number.isInteger(v.cf) || v.cf < 0 || v.cf > 4294967295) throw new Invalid("cf"); out.cf = v.cf }
  return out;
}

const CLS = /^[\w$.\/<>\-]{1,200}$/;
const METHOD = /^[\w$<>\-]{1,120}$/;
const SRC = /^[\w$.\-]{1,80}(?::\d{1,7})?$/;
const CAUSE = /^[A-Z_]{1,40}$/;
const RANGE = /^[\w.+\-\[\]\(\), *<>=~^|]{1,64}$/;
const CONTEXTS = new Set(["launch", "ingame", "tune"]);
const SOURCES = new Set(["crash-report", "hs_err", "log"]);

export function scrub(s: string, max: number): string {
  return s
    .replace(/[A-Za-z]:[\\/][^\s"']*/g, "<path>")
    .replace(/(?:\/(?:Users|home|mnt|var|tmp|opt)\/)[^\s"']*/gi, "<path>")
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, "")
    .slice(0, max);
}

function text(v: any, max: number): string | undefined {
  if (v === undefined || v === null) return undefined;
  if (!str(v)) throw new Invalid("text");
  const t = scrub(v, max).trim();
  return t.length ? t : undefined;
}

function arr(v: any, max: number): any[] {
  if (v === undefined || v === null) return [];
  if (!Array.isArray(v) || v.length > max) throw new Invalid("array");
  return v;
}

function crash(v: any): Item {
  if (!CONTEXTS.has(v.context)) throw new Invalid("context");
  if (!SOURCES.has(v.source)) throw new Invalid("source");
  if (!str(v.cause) || !CAUSE.test(v.cause)) throw new Invalid("cause");
  const frames = arr(v.frames, 40).map((f: any) => {
    if (!f || !str(f.c) || !CLS.test(f.c) || !str(f.m) || !METHOD.test(f.m)) throw new Invalid("frame");
    const out: any = { c: f.c, m: f.m };
    if (f.src !== undefined && f.src !== null) { if (!str(f.src) || !SRC.test(f.src)) throw new Invalid("frame src"); out.src = f.src }
    if (f.mod !== undefined && f.mod !== null) { if (!str(f.mod) || !ID.test(f.mod)) throw new Invalid("frame mod"); out.mod = f.mod.toLowerCase() }
    return out;
  });
  const causes = arr(v.causes, 8).map((c: any) => {
    if (!c || !str(c.ex) || !CLS.test(c.ex)) throw new Invalid("cause ex");
    const out: any = { ex: c.ex }; const m = text(c.msg, 300); if (m) out.msg = m; return out;
  });
  const findings = arr(v.findings, 16).map((f: any) => {
    if (!f || !str(f.cause) || !CAUSE.test(f.cause)) throw new Invalid("finding");
    const out: any = { cause: f.cause };
    for (const k of ["mod", "other", "required", "actual", "config"]) { const t = text(f[k], 160); if (t) out[k] = t }
    const line = text(f.line, 300); if (line) out.line = line;
    return out;
  });
  const sys: Record<string, string> = {};
  if (v.sys !== undefined && v.sys !== null) {
    if (typeof v.sys !== "object" || Array.isArray(v.sys)) throw new Invalid("sys");
    const entries = Object.entries(v.sys);
    if (entries.length > 24) throw new Invalid("sys");
    for (const [k, val] of entries) { if (!/^[\w #.\-]{1,40}$/.test(k) || !str(val)) throw new Invalid("sys"); sys[k] = scrub(val, 160) }
  }
  const out: any = { type: "crash", context: v.context, source: v.source, cause: v.cause, causes, frames, mods: refs(v.mods ?? [], 0, 16), findings, sys };
  const fp = opt(v.fp, HEX); if (fp) out.fp = fp;
  if (v.ex !== undefined && v.ex !== null) { if (!str(v.ex) || !CLS.test(v.ex)) throw new Invalid("ex"); out.ex = v.ex }
  const msg = text(v.msg, 300); if (msg) out.msg = msg;
  const phase = text(v.phase, 120); if (phase) out.phase = phase;
  if (typeof v.heapMb === "number" && v.heapMb > 0 && v.heapMb < 1048576) out.heapMb = Math.floor(v.heapMb);
  if (typeof v.seen === "number" && v.seen > 0 && v.seen < 10000) out.seen = Math.floor(v.seen);
  return out;
}

function refs(v: any, min: number, max: number): ModRef[] {
  if (!Array.isArray(v) || v.length < min || v.length > max) throw new Invalid("mods");
  return v.map(ref);
}

function opt(v: any, re: RegExp): string | undefined {
  if (v === undefined || v === null) return undefined;
  if (!str(v) || !re.test(v)) throw new Invalid("field");
  return v;
}

function item(v: any): Item {
  switch (v?.type) {
    case "conflict": {
      if (v.method !== "bisect" && v.method !== "diagnosis") throw new Invalid("method");
      const trials = typeof v.trials === "number" && v.trials >= 0 && v.trials < 10000 ? Math.floor(v.trials) : undefined;
      return { type: "conflict", mods: refs(v.mods, 1, 4), fp: opt(v.fp, HEX), ex: str(v.ex) && EXNAME.test(v.ex) ? v.ex : undefined, method: v.method, trials };
    }
    case "fix": {
      if (!str(v.kind) || !KIND.test(v.kind)) throw new Invalid("kind");
      if (v.result !== "fixed" && v.result !== "changed" && v.result !== "failed") throw new Invalid("result");
      const from = refs(v.from ?? [], 0, 16), to = refs(v.to ?? [], 0, 16);
      if (from.length + to.length === 0) throw new Invalid("empty fix");
      return { type: "fix", kind: v.kind, from, to, result: v.result, fp: opt(v.fp, HEX) };
    }
    case "broken": {
      if (!str(v.reason) || !REASON.test(v.reason)) throw new Invalid("reason");
      return { type: "broken", mod: ref(v.mod), reason: v.reason };
    }
    case "compatible":
      return { type: "compatible", mods: refs(v.mods, 2, 2000) };
    case "crash":
      return crash(v);
    case "dependency": {
      if (typeof v.declared !== "boolean") throw new Invalid("declared");
      const out: any = { type: "dependency", mod: ref(v.mod), needs: ref(v.needs), declared: v.declared };
      if (v.range !== undefined && v.range !== null) { if (!str(v.range) || !RANGE.test(v.range)) throw new Invalid("range"); out.range = v.range }
      return out;
    }
    default:
      throw new Invalid("item type");
  }
}

function env(v: any): Env {
  if (!v || !str(v.mc) || !MC.test(v.mc)) throw new Invalid("mc");
  if (!str(v.loader) || !LOADERS.has(v.loader)) throw new Invalid("loader");
  const out: Env = { mc: v.mc, loader: v.loader };
  if (v.loaderVersion !== undefined && v.loaderVersion !== null) { if (!str(v.loaderVersion) || !VER.test(v.loaderVersion)) throw new Invalid("loaderVersion"); out.loaderVersion = v.loaderVersion }
  if (typeof v.java === "number" && v.java > 0 && v.java < 100) out.java = Math.floor(v.java);
  const os = text(v.os, 32); if (os && /^[\w .\-]{1,32}$/.test(os)) out.os = os;
  const arch = text(v.arch, 16); if (arch && /^[\w.\-]{1,16}$/.test(arch)) out.arch = arch;
  return out;
}

export function validate(v: any): Report {
  if (!v || (v.schema !== undefined && v.schema !== 1)) throw new Invalid("schema");
  if (!str(v.install) || !INSTALL.test(v.install)) throw new Invalid("install");
  if (!str(v.app) || v.app.length > 32) throw new Invalid("app");
  if (!Array.isArray(v.items) || v.items.length === 0 || v.items.length > 50) throw new Invalid("items");
  const time = typeof v.time === "number" ? v.time : 0;
  return { schema: 1, install: v.install, app: v.app, time, env: env(v.env), items: v.items.map(item) };
}
