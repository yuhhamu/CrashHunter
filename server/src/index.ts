import { aggregate } from "./aggregate";
import { ADMIN_HTML, ADMIN_JS } from "./adminUi";
import type { Item, Snapshot, StoredItem } from "./types";
import { Invalid, validate } from "./validate";
import { accessIdentity, latestVerdicts, parseVerdict, pending, saveVerdict } from "./verify";

export interface Bindings {
  DB: D1Database;
  SNAP: KVNamespace;
  APP_TOKEN?: string;
  ADMIN_TOKEN?: string;
  SALT?: string;
  LIMIT_PER_INSTALL_DAY?: string;
  LIMIT_PER_IP_DAY?: string;
  LIMIT_CHECKS_PER_IP_DAY?: string;
  MAX_BODY?: string;
  MAX_RUN_BODY?: string;
  LIMIT_RUNS_PER_INSTALL_DAY?: string;
  ACCESS_TEAM?: string;
  ACCESS_AUD?: string;
  ACCESS_JWKS?: string;
}

const MC = /^[0-9a-z.\-]{1,24}$/i;
const LOADER = /^[a-z_]{1,16}$/;

function page(body: string, type: string): Response {
  return new Response(body, {
    headers: {
      "content-type": `${type}; charset=utf-8`,
      "content-security-policy": "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
      "x-frame-options": "DENY",
      "x-content-type-options": "nosniff",
      "referrer-policy": "no-referrer",
      "x-robots-tag": "noindex, nofollow",
      "cache-control": "no-store",
    },
  });
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8", ...headers } });
}

const error = (status: number, message: string) => json({ error: message }, status);

async function sha256(text: string): Promise<string> {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

const hashInstall = (env: Bindings, install: string) => sha256(`${env.SALT ?? ""}:install:${install.toLowerCase()}`);
const hashIp = (env: Bindings, ip: string) => sha256(`${env.SALT ?? ""}:ip:${ip}`);

export function ipv6Prefix(ip: string): string | null {
  if (!ip.includes(":")) return null;
  const [head, tail] = ip.toLowerCase().split("::");
  const a = head ? head.split(":") : [];
  const b = tail !== undefined && tail.length ? tail.split(":") : [];
  if (tail === undefined && a.length !== 8) return null;
  const full = tail === undefined ? a : [...a, ...Array(Math.max(0, 8 - a.length - b.length)).fill("0"), ...b];
  if (full.length !== 8 || full.some((g) => !/^[0-9a-f]{1,4}$/.test(g))) return null;
  return full.slice(0, 4).map((g) => g.replace(/^0+(?=.)/, "")).join(":") + "::/64";
}

async function ipKeys(env: Bindings, raw: string): Promise<string[]> {
  const keys = [await hashIp(env, raw)];
  const prefix = ipv6Prefix(raw);
  if (prefix) keys.push(await hashIp(env, prefix));
  return keys;
}

async function ipBlocked(env: Bindings, keys: string[], now: number): Promise<boolean> {
  const marks = keys.map((_, i) => `?${i + 2}`).join(", ");
  const row = await env.DB.prepare(`SELECT 1 FROM blocked_ip WHERE ip IN (${marks}) AND (expires IS NULL OR expires > ?1) LIMIT 1`).bind(now, ...keys).first();
  return !!row;
}

function appOk(req: Request, env: Bindings): boolean {
  return !!env.APP_TOKEN && req.headers.get("x-ch-token") === env.APP_TOKEN;
}

const rateKey = (keys: string[]) => keys[keys.length - 1];

function adminOk(req: Request, env: Bindings): boolean {
  return !!env.ADMIN_TOKEN && req.headers.get("authorization") === `Bearer ${env.ADMIN_TOKEN}`;
}

const day = (now: number) => Math.floor(now / 86_400_000);

async function bump(env: Bindings, bucket: string, now: number): Promise<number> {
  const expires = (day(now) + 2) * 86_400_000;
  const row = await env.DB.prepare(
    "INSERT INTO rate (bucket, count, expires) VALUES (?1, 1, ?2) ON CONFLICT(bucket) DO UPDATE SET count = count + 1 RETURNING count",
  ).bind(bucket, expires).first<{ count: number }>();
  return row?.count ?? 1;
}

async function markDirty(env: Bindings, mc: string, loader: string) {
  await env.DB.prepare("INSERT OR IGNORE INTO dirty (mc, loader) VALUES (?1, ?2)").bind(mc, loader).run();
}

async function readBody(req: Request, max: number): Promise<string | null> {
  const len = Number(req.headers.get("content-length") ?? "0");
  if (len > max) return null;
  const text = await req.text();
  return text.length > max ? null : text;
}

async function postReport(req: Request, env: Bindings, now: number): Promise<Response> {
  if (!appOk(req, env)) return error(401, "token");
  const text = await readBody(req, Number(env.MAX_BODY ?? "262144"));
  if (text === null) return error(413, "too large");
  let report;
  try { report = validate(JSON.parse(text)) } catch (e) {
    return error(400, e instanceof Invalid ? `invalid: ${e.message}` : "invalid json");
  }
  const install = await hashInstall(env, report.install);
  const blocked = await env.DB.prepare("SELECT 1 FROM blocked WHERE install = ?1").bind(install).first();
  if (blocked) return error(403, "blocked");
  const keys = await ipKeys(env, req.headers.get("cf-connecting-ip") ?? "unknown");
  if (await ipBlocked(env, keys, now)) return error(403, "blocked");
  const ip = keys[0];
  const d = day(now);
  if (await bump(env, `i:${install}:${d}`, now) > Number(env.LIMIT_PER_INSTALL_DAY ?? "60")) return error(429, "rate");
  if (await bump(env, `p:${rateKey(keys)}:${d}`, now) > Number(env.LIMIT_PER_IP_DAY ?? "300")) return error(429, "rate");
  const stored = { ...report, install: undefined };
  const res = await env.DB.prepare("INSERT INTO reports (created, install, ip, app, mc, loader, body) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7) RETURNING id")
    .bind(now, install, ip, report.app, report.env.mc, report.env.loader, JSON.stringify(stored)).first<{ id: number }>();
  const id = res!.id;
  await env.DB.batch(report.items.map((it) =>
    env.DB.prepare("INSERT INTO items (report_id, install, created, mc, loader, kind, data) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)")
      .bind(id, install, now, report.env.mc, report.env.loader, it.type, JSON.stringify(it))));
  await markDirty(env, report.env.mc, report.env.loader);
  return json({ ok: true, items: report.items.length });
}

const KINDS = new Set(["bisect", "tune"]);
const STATUS = /^[A-Z_]{2,32}$/;

async function postRun(req: Request, env: Bindings, now: number): Promise<Response> {
  if (!appOk(req, env)) return error(401, "token");
  const text = await readBody(req, Number(env.MAX_RUN_BODY ?? "262144"));
  if (text === null) return error(413, "too large");
  let run: any;
  try { run = JSON.parse(text) } catch { return error(400, "invalid json") }
  if (!run || typeof run !== "object" || Array.isArray(run)) return error(400, "invalid: body");
  if (run.schema !== undefined && run.schema !== 1) return error(400, "invalid: schema");
  if (typeof run.install !== "string" || !/^[0-9a-f\-]{8,64}$/i.test(run.install)) return error(400, "invalid: install");
  if (typeof run.app !== "string" || run.app.length > 32) return error(400, "invalid: app");
  if (!KINDS.has(run.kind)) return error(400, "invalid: kind");
  if (typeof run.status !== "string" || !STATUS.test(run.status)) return error(400, "invalid: status");
  const e = run.env;
  if (!e || typeof e.mc !== "string" || typeof e.loader !== "string" || !MC.test(e.mc) || !LOADER.test(e.loader)) return error(400, "invalid: env");
  if (!Array.isArray(run.trials) || run.trials.length > 2000) return error(400, "invalid: trials");
  if (!Array.isArray(run.mods) || run.mods.length > 2000) return error(400, "invalid: mods");
  const install = await hashInstall(env, run.install);
  const blocked = await env.DB.prepare("SELECT 1 FROM blocked WHERE install = ?1").bind(install).first();
  if (blocked) return error(403, "blocked");
  const keys = await ipKeys(env, req.headers.get("cf-connecting-ip") ?? "unknown");
  if (await ipBlocked(env, keys, now)) return error(403, "blocked");
  const ip = keys[0];
  const d = day(now);
  if (await bump(env, `ri:${install}:${d}`, now) > Number(env.LIMIT_RUNS_PER_INSTALL_DAY ?? "40")) return error(429, "rate");
  if (await bump(env, `rp:${rateKey(keys)}:${d}`, now) > Number(env.LIMIT_PER_IP_DAY ?? "300")) return error(429, "rate");
  delete run.install;
  await env.DB.prepare("INSERT INTO runs (created, install, ip, app, kind, mc, loader, status, body) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)")
    .bind(now, install, ip, run.app, run.kind, e.mc, e.loader, run.status, JSON.stringify(run)).run();
  return json({ ok: true });
}

function emptySnapshot(mc: string, loader: string): Snapshot {
  return { schema: 1, mc, loader, generated: 0, conflicts: [], fixes: [], broken: [] };
}

async function getSnapshot(req: Request, env: Bindings, url: URL): Promise<Response> {
  if (!appOk(req, env)) return error(401, "token");
  const mc = url.searchParams.get("mc") ?? "", loader = url.searchParams.get("loader") ?? "";
  if (!MC.test(mc) || !LOADER.test(loader)) return error(400, "env");
  const { value, metadata } = await env.SNAP.getWithMetadata<{ generated: number }>(`snap/${mc}/${loader}`);
  if (!value) return json(emptySnapshot(mc, loader));
  const etag = `"${metadata?.generated ?? 0}"`;
  if (req.headers.get("if-none-match") === etag) return new Response(null, { status: 304, headers: { etag } });
  return new Response(value, { headers: { "content-type": "application/json; charset=utf-8", etag, "cache-control": "max-age=600" } });
}

type Ref = { id: string; v?: string; sha1?: string };

function blamed(item: any): Ref[] {
  const refs = (item.mods ?? []) as Ref[];
  const ids = new Set<string>();
  for (const f of item.findings ?? []) if (typeof f.mod === "string") ids.add(f.mod.toLowerCase());
  if (!ids.size) {
    const first = (item.frames ?? []).find((f: any) => typeof f.mod === "string");
    if (first) ids.add(first.mod.toLowerCase());
  }
  return [...ids].map((id) => refs.find((m) => m.id === id) ?? { id });
}

async function check(req: Request, env: Bindings, now: number): Promise<Response> {
  if (!appOk(req, env)) return error(401, "token");
  const text = await readBody(req, Number(env.MAX_BODY ?? "262144"));
  if (text === null) return error(413, "too large");
  let body: any;
  try { body = JSON.parse(text) } catch { return error(400, "invalid json") }
  const e = body?.env;
  if (!e || typeof e.mc !== "string" || typeof e.loader !== "string" || !MC.test(e.mc) || !LOADER.test(e.loader)) return error(400, "invalid: env");
  if (!Array.isArray(body.mods) || body.mods.length === 0 || body.mods.length > 2000) return error(400, "invalid: mods");
  const mods: Ref[] = body.mods.filter((m: any) => m && typeof m.id === "string" && m.id.length <= 64).map((m: any) => ({ id: m.id.toLowerCase(), v: typeof m.v === "string" ? m.v : undefined, sha1: typeof m.sha1 === "string" ? m.sha1 : undefined }));
  const keys = await ipKeys(env, req.headers.get("cf-connecting-ip") ?? "unknown");
  if (await ipBlocked(env, keys, now)) return error(403, "blocked");
  if (await bump(env, `c:${rateKey(keys)}:${day(now)}`, now) > Number(env.LIMIT_CHECKS_PER_IP_DAY ?? "200")) return error(429, "rate");

  const same = (a: Ref, b: Ref) => a.id === b.id && ((!!a.sha1 && a.sha1 === b.sha1) || (!!a.v && a.v === b.v));
  const mine = (r: Ref) => mods.some((m) => same(m, r));
  const hasId = (id: string) => mods.some((m) => m.id === id);

  const snapText = await env.SNAP.get(`snap/${e.mc}/${e.loader}`);
  const snap = snapText ? JSON.parse(snapText) : { conflicts: [], fixes: [], broken: [] };
  const broken = (snap.broken as any[]).filter((b) => mine(b.mod)).map((b) => ({ mod: b.mod, reason: b.reason, n: b.n, confirmed: b.confirmed === true }));
  const conflicts = (snap.conflicts as any[]).filter((c) => c.mods.length && c.mods.every((m: Ref) => mine(m))).map((c) => ({ mods: c.mods, ex: c.ex, n: c.n }));
  const fixes = (snap.fixes as any[]).filter((f) => f.from.length === 1 && mine(f.from[0])).map((f) => ({ kind: f.kind, from: f.from, to: f.to, n: f.n }));

  const rows = await env.DB.prepare("SELECT install, kind, data FROM items WHERE mc = ?1 AND loader = ?2 AND kind IN ('compatible', 'crash') ORDER BY id DESC LIMIT 4000")
    .bind(e.mc, e.loader).all<{ install: string; kind: string; data: string }>();
  let compatibleReports = 0, supersets = 0, best = 0;
  const seen = new Set<string>();
  const superInstalls = new Set<string>();
  const crashes = new Map<string, { mod: Ref; installs: Set<string>; causes: Map<string, Set<string>> }>();
  for (const r of rows.results) {
    const item = JSON.parse(r.data);
    if (item.type === "compatible") {
      compatibleReports++;
      let hit = 0;
      for (const m of mods) if ((item.mods as Ref[]).some((x) => same(m, x))) { hit++; seen.add(m.id); }
      if (hit === mods.length) { supersets++; superInstalls.add(r.install); }
      best = Math.max(best, hit / mods.length);
    } else if (item.type === "crash" && item.context !== "tune") {
      for (const m of blamed(item).filter(mine)) {
        const k = `${m.id}@${m.v ?? ""}`;
        const c = crashes.get(k) ?? { mod: { id: m.id, v: m.v }, installs: new Set<string>(), causes: new Map<string, Set<string>>() };
        c.installs.add(r.install);
        const s = c.causes.get(item.cause) ?? new Set<string>();
        s.add(r.install);
        c.causes.set(item.cause, s);
        crashes.set(k, c);
      }
    }
  }
  return json({
    generated: snap.generated ?? 0,
    known: { broken, conflicts, fixes },
    compatible: {
      reports: compatibleReports, supersets, installs: superInstalls.size, best: Math.round(best * 1000) / 1000,
      unseen: compatibleReports ? mods.filter((m) => !seen.has(m.id) && hasId(m.id)).map((m) => ({ id: m.id, v: m.v })).slice(0, 300) : [],
    },
    crashes: [...crashes.values()].map((c) => ({ mod: c.mod, installs: c.installs.size, causes: Object.fromEntries([...c.causes].map(([k, v]) => [k, v.size])) })).sort((a, b) => b.installs - a.installs).slice(0, 100),
  });
}

async function forget(req: Request, env: Bindings): Promise<Response> {
  if (!appOk(req, env)) return error(401, "token");
  const text = await readBody(req, 1024);
  let raw: string | undefined;
  try { raw = JSON.parse(text ?? "{}").install } catch { return error(400, "invalid json") }
  if (typeof raw !== "string" || !/^[0-9a-f\-]{8,64}$/i.test(raw)) return error(400, "install");
  const n = await removeInstall(env, await hashInstall(env, raw));
  return json({ ok: true, removed: n });
}

async function removeInstall(env: Bindings, install: string): Promise<number> {
  const envs = await env.DB.prepare("SELECT DISTINCT mc, loader FROM reports WHERE install = ?1").bind(install).all<{ mc: string; loader: string }>();
  const r = await env.DB.prepare("DELETE FROM reports WHERE install = ?1").bind(install).run();
  await env.DB.prepare("DELETE FROM items WHERE install = ?1").bind(install).run();
  const runs = await env.DB.prepare("DELETE FROM runs WHERE install = ?1").bind(install).run();
  for (const e of envs.results) await markDirty(env, e.mc, e.loader);
  return (r.meta.changes ?? 0) + (runs.meta.changes ?? 0);
}

export async function rebuild(env: Bindings, now = Date.now()): Promise<number> {
  const dirty = await env.DB.prepare("SELECT mc, loader FROM dirty").all<{ mc: string; loader: string }>();
  const verdicts = dirty.results.length ? await latestVerdicts(env.DB) : [];
  for (const { mc, loader } of dirty.results) {
    const rows = await env.DB.prepare("SELECT install, data FROM items WHERE mc = ?1 AND loader = ?2 ORDER BY id DESC LIMIT 200000")
      .bind(mc, loader).all<{ install: string; data: string }>();
    const items: StoredItem[] = rows.results.map((r) => ({ install: r.install, item: JSON.parse(r.data) as Item }));
    const snap = aggregate(mc, loader, items, now, verdicts);
    await env.SNAP.put(`snap/${mc}/${loader}`, JSON.stringify(snap), { metadata: { generated: snap.generated } });
    await env.DB.prepare("DELETE FROM dirty WHERE mc = ?1 AND loader = ?2").bind(mc, loader).run();
  }
  await env.DB.prepare("DELETE FROM rate WHERE expires < ?1").bind(now).run();
  await env.DB.prepare("DELETE FROM blocked_ip WHERE expires IS NOT NULL AND expires < ?1").bind(now).run();
  return dirty.results.length;
}

function mentions(item: any, id: string): boolean {
  const has = (r: any) => r && r.id === id;
  switch (item.type) {
    case "conflict": case "compatible": return item.mods.some(has);
    case "fix": return item.from.some(has) || item.to.some(has);
    case "broken": return has(item.mod);
    case "dependency": return has(item.mod) || has(item.needs);
    case "crash": return item.mods.some(has) || item.frames.some((f: any) => f.mod === id) || item.findings.some((f: any) => f.mod === id);
    default: return false;
  }
}

async function blockIp(env: Bindings, text: string): Promise<Response> {
  let body: any;
  try { body = JSON.parse(text) } catch { return error(400, "invalid json") }
  const now = Date.now();
  let keys: string[] = [];
  if (typeof body.ip === "string" && body.ip.length <= 64) keys = await ipKeys(env, body.ip.trim());
  else if (typeof body.ipHash === "string" && /^[0-9a-f]{16,128}$/.test(body.ipHash)) keys = [body.ipHash];
  else if (Number.isInteger(body.reportId)) {
    const r = await env.DB.prepare("SELECT ip FROM reports WHERE id = ?1").bind(body.reportId).first<{ ip: string }>();
    if (r) keys = [r.ip];
  } else if (Number.isInteger(body.runId)) {
    const r = await env.DB.prepare("SELECT ip FROM runs WHERE id = ?1").bind(body.runId).first<{ ip: string }>();
    if (r) keys = [r.ip];
  }
  if (!keys.length) return error(400, "target");
  const days = typeof body.days === "number" && body.days >= 0 && body.days <= 3650 ? body.days : 30;
  const expires = days === 0 ? null : now + days * 86_400_000;
  for (const k of keys) {
    await env.DB.prepare("INSERT OR REPLACE INTO blocked_ip (ip, reason, created, expires) VALUES (?1, ?2, ?3, ?4)").bind(k, String(body.reason ?? "").slice(0, 200), now, expires).run();
  }
  let removed = 0;
  if (body.purge === true) {
    for (const k of keys) {
      const envs = await env.DB.prepare("SELECT DISTINCT mc, loader FROM reports WHERE ip = ?1").bind(k).all<{ mc: string; loader: string }>();
      await env.DB.prepare("DELETE FROM items WHERE report_id IN (SELECT id FROM reports WHERE ip = ?1)").bind(k).run();
      const r = await env.DB.prepare("DELETE FROM reports WHERE ip = ?1").bind(k).run();
      const runs = await env.DB.prepare("DELETE FROM runs WHERE ip = ?1").bind(k).run();
      removed += (r.meta.changes ?? 0) + (runs.meta.changes ?? 0);
      for (const e of envs.results) await markDirty(env, e.mc, e.loader);
    }
  }
  return json({ ok: true, blocked: keys, expires, removed });
}

async function modView(env: Bindings, url: URL) {
  const id = (url.searchParams.get("id") ?? "").toLowerCase();
  if (!/^[a-z0-9_.\-]{1,64}$/.test(id)) return { error: "id" };
  const mc = url.searchParams.get("mc"), loader = url.searchParams.get("loader");
  const like = `%"id":"${id}"%`, like2 = `%"mod":"${id}"%`;
  const rows = await env.DB.prepare("SELECT install, created, mc, loader, kind, data FROM items WHERE (data LIKE ?1 OR data LIKE ?2) AND (?3 IS NULL OR mc = ?3) AND (?4 IS NULL OR loader = ?4) ORDER BY id DESC LIMIT 5000")
    .bind(like, like2, mc, loader).all<{ install: string; created: number; mc: string; loader: string; kind: string; data: string }>();
  const installs = new Set<string>();
  const crashes = new Map<string, any>();
  const conflicts = new Map<string, any>();
  const fixes = new Map<string, any>();
  const deps = new Map<string, any>();
  const broken = new Map<string, any>();
  let compatible = 0;
  const versions = (refs: any[]) => refs.filter((r) => r.id === id).map((r) => r.v ?? "?");
  for (const r of rows.results) {
    const item = JSON.parse(r.data);
    if (!mentions(item, id)) continue;
    installs.add(r.install);
    const envKey = `${r.mc} ${r.loader}`;
    if (item.type === "crash") {
      const k = item.fp ?? `${item.cause}|${item.ex ?? ""}`;
      const e = crashes.get(k) ?? { fp: item.fp, cause: item.cause, ex: item.ex, msg: item.msg, phase: item.phase, causes: item.causes, frames: item.frames.slice(0, 12), installs: new Set(), contexts: {}, envs: new Set(), versions: new Set(), withMods: {}, sys: [], first: r.created, last: r.created, seen: 0 };
      e.installs.add(r.install); e.envs.add(envKey);
      e.contexts[item.context] = (e.contexts[item.context] ?? 0) + 1;
      versions(item.mods).forEach((v: string) => e.versions.add(v));
      for (const m of item.mods) if (m.id !== id) e.withMods[m.id] = (e.withMods[m.id] ?? 0) + 1;
      if (e.sys.length < 5 && Object.keys(item.sys ?? {}).length) e.sys.push(item.sys);
      e.first = Math.min(e.first, r.created); e.last = Math.max(e.last, r.created); e.seen += item.seen ?? 1;
      crashes.set(k, e);
    } else if (item.type === "conflict") {
      const k = item.mods.map((m: any) => m.id).sort().join("+");
      const e = conflicts.get(k) ?? { mods: item.mods.map((m: any) => `${m.id}@${m.v ?? "?"}`), ex: item.ex, installs: new Set(), envs: new Set() };
      e.installs.add(r.install); e.envs.add(envKey); conflicts.set(k, e);
    } else if (item.type === "fix") {
      const from = item.from.map((m: any) => `${m.id}@${m.v ?? "?"}`).join(","), to = item.to.map((m: any) => `${m.id}@${m.v ?? "?"}`).join(",");
      const k = `${item.kind}|${from}>${to}`;
      const e = fixes.get(k) ?? { kind: item.kind, from, to, fixed: new Set(), changed: new Set(), failed: new Set() };
      e[item.result].add(r.install); fixes.set(k, e);
    } else if (item.type === "dependency") {
      const k = `${item.mod.id}>${item.needs.id}`;
      const e = deps.get(k) ?? { mod: item.mod.id, needs: item.needs.id, declared: item.declared, range: item.range, installs: new Set() };
      e.installs.add(r.install); deps.set(k, e);
    } else if (item.type === "broken") {
      const k = `${item.mod.v ?? "?"}|${item.reason}`;
      const e = broken.get(k) ?? { version: item.mod.v, reason: item.reason, installs: new Set() };
      e.installs.add(r.install); broken.set(k, e);
    } else if (item.type === "compatible") compatible++;
  }
  const n = (s: Set<string>) => s.size;
  const by = (a: any, b: any) => b.installs - a.installs;
  return {
    mod: id, installs: installs.size, compatibleReports: compatible,
    crashes: [...crashes.values()].map((e) => ({ ...e, installs: n(e.installs), envs: [...e.envs], versions: [...e.versions] })).sort(by),
    conflicts: [...conflicts.values()].map((e) => ({ ...e, installs: n(e.installs), envs: [...e.envs] })).sort(by),
    fixes: [...fixes.values()].map((e) => ({ kind: e.kind, from: e.from, to: e.to, fixed: n(e.fixed), changed: n(e.changed), failed: n(e.failed) })),
    dependencies: [...deps.values()].map((e) => ({ ...e, installs: n(e.installs) })).sort(by),
    broken: [...broken.values()].map((e) => ({ ...e, installs: n(e.installs) })).sort(by),
  };
}

async function verdictPost(req: Request, env: Bindings, by: string): Promise<Response> {
  let v;
  try { v = parseVerdict(JSON.parse((await readBody(req, 4096)) ?? "null")) } catch (e) { return error(400, `invalid: ${(e as Error).message}`) }
  await saveVerdict(env.DB, v, by, Date.now());
  await rebuild(env);
  return json({ ok: true });
}

async function verify(req: Request, env: Bindings, url: URL): Promise<Response> {
  if (!env.ACCESS_TEAM || !env.ACCESS_AUD) return error(503, "verify disabled");
  const who = await accessIdentity(req, env.ACCESS_TEAM, env.ACCESS_AUD, env.ACCESS_JWKS);
  if (!who) return error(401, "access");
  const path = url.pathname.replace("/v1/verify/", "");
  if (path === "pending" && req.method === "GET") return json(await pending(env.SNAP, await latestVerdicts(env.DB)));
  if (path === "result" && req.method === "POST") return await verdictPost(req, env, who);
  if (path === "whoami" && req.method === "GET") return json({ who });
  return error(404, "not found");
}

async function admin(req: Request, env: Bindings, url: URL): Promise<Response> {
  if (!adminOk(req, env)) return error(401, "admin");
  const path = url.pathname.replace("/v1/admin/", "");
  if (path === "stats" && req.method === "GET") {
    const r = await env.DB.prepare("SELECT (SELECT COUNT(*) FROM reports) AS reports, (SELECT COUNT(*) FROM items) AS items, (SELECT COUNT(DISTINCT install) FROM reports) AS installs, (SELECT COUNT(*) FROM blocked) AS blocked, (SELECT COUNT(*) FROM runs) AS runs, (SELECT COUNT(*) FROM blocked_ip WHERE expires IS NULL OR expires > ?1) AS blockedIps, (SELECT COUNT(*) FROM reports WHERE created > ?2) AS reports24h, (SELECT COUNT(*) FROM runs WHERE created > ?2) AS runs24h").bind(Date.now(), Date.now() - 86_400_000).first();
    return json(r);
  }
  if (path === "rebuild" && req.method === "POST") {
    await env.DB.prepare("INSERT OR IGNORE INTO dirty (mc, loader) SELECT DISTINCT mc, loader FROM items").run();
    return json({ ok: true, rebuilt: await rebuild(env) });
  }
  if (path === "block" && req.method === "POST") {
    const body = JSON.parse((await readBody(req, 2048)) ?? "{}");
    const install = typeof body.installHash === "string" ? body.installHash : typeof body.install === "string" ? await hashInstall(env, body.install) : null;
    if (!install) return error(400, "install");
    await env.DB.prepare("INSERT OR REPLACE INTO blocked (install, reason, created) VALUES (?1, ?2, ?3)").bind(install, String(body.reason ?? ""), Date.now()).run();
    return json({ ok: true, removed: await removeInstall(env, install) });
  }
  if (path === "export" && req.method === "GET") {
    const mc = url.searchParams.get("mc") ?? "", loader = url.searchParams.get("loader") ?? "";
    if (!MC.test(mc) || !LOADER.test(loader)) return error(400, "env");
    const value = await env.SNAP.get(`snap/${mc}/${loader}`);
    return value ? new Response(value, { headers: { "content-type": "application/json; charset=utf-8" } }) : json(emptySnapshot(mc, loader));
  }
  if (path === "runs" && req.method === "GET") {
    const limit = Math.min(500, Number(url.searchParams.get("limit") ?? "50"));
    const kind = url.searchParams.get("kind");
    const before = Number(url.searchParams.get("before") ?? "0") || 0;
    const q = env.DB.prepare("SELECT id, created, install, ip, app, kind, mc, loader, status, body FROM runs WHERE (?2 IS NULL OR kind = ?2) AND (?3 = 0 OR id < ?3) ORDER BY id DESC LIMIT ?1")
      .bind(limit, kind && KINDS.has(kind) ? kind : null, before);
    return json((await q.all()).results);
  }
  if (path === "mod" && req.method === "GET") return json(await modView(env, url));
  if (path === "pending" && req.method === "GET") return json(await pending(env.SNAP, await latestVerdicts(env.DB)));
  if (path === "verdicts" && req.method === "GET") return json(await latestVerdicts(env.DB));
  if (path === "verdict" && req.method === "POST") return await verdictPost(req, env, "admin");
  if (path === "block-ip" && req.method === "POST") return await blockIp(env, (await readBody(req, 2048)) ?? "{}");
  if (path === "unblock-ip" && req.method === "POST") {
    const body = JSON.parse((await readBody(req, 1024)) ?? "{}");
    if (typeof body.ipHash !== "string") return error(400, "ipHash");
    const r = await env.DB.prepare("DELETE FROM blocked_ip WHERE ip = ?1").bind(body.ipHash).run();
    return json({ ok: true, removed: r.meta.changes ?? 0 });
  }
  if (path === "blocked-ips" && req.method === "GET") {
    const r = await env.DB.prepare("SELECT ip, reason, created, expires FROM blocked_ip ORDER BY created DESC LIMIT 500").all();
    return json(r.results);
  }
  if (path === "reports" && req.method === "GET") {
    const limit = Math.min(200, Math.max(1, Number(url.searchParams.get("limit") ?? "50") || 50));
    const before = Number(url.searchParams.get("before") ?? "0") || 0;
    const mc = url.searchParams.get("mc") || null, install = url.searchParams.get("install") || null, mod = url.searchParams.get("mod") || null;
    const r = await env.DB.prepare("SELECT id, created, install, ip, app, mc, loader, body FROM reports WHERE (?2 = 0 OR id < ?2) AND (?3 IS NULL OR mc = ?3) AND (?4 IS NULL OR install = ?4) AND (?5 IS NULL OR body LIKE ?5) ORDER BY id DESC LIMIT ?1")
      .bind(limit, before, mc, install, mod ? `%"id":"${mod.toLowerCase().replace(/[^a-z0-9_.\-]/g, "")}"%` : null).all();
    return json(r.results);
  }
  if (path === "delete-report" && req.method === "POST") {
    const body = JSON.parse((await readBody(req, 512)) ?? "{}");
    if (!Number.isInteger(body.id)) return error(400, "id");
    const row = await env.DB.prepare("SELECT mc, loader FROM reports WHERE id = ?1").bind(body.id).first<{ mc: string; loader: string }>();
    if (!row) return json({ ok: true, removed: 0 });
    await env.DB.prepare("DELETE FROM items WHERE report_id = ?1").bind(body.id).run();
    await env.DB.prepare("DELETE FROM reports WHERE id = ?1").bind(body.id).run();
    await markDirty(env, row.mc, row.loader);
    return json({ ok: true, removed: 1 });
  }
  if (path === "delete-run" && req.method === "POST") {
    const body = JSON.parse((await readBody(req, 512)) ?? "{}");
    if (!Number.isInteger(body.id)) return error(400, "id");
    const r = await env.DB.prepare("DELETE FROM runs WHERE id = ?1").bind(body.id).run();
    return json({ ok: true, removed: r.meta.changes ?? 0 });
  }
  if (path === "blocked" && req.method === "GET") {
    const r = await env.DB.prepare("SELECT install, reason, created FROM blocked ORDER BY created DESC LIMIT 500").all();
    return json(r.results);
  }
  if (path === "unblock" && req.method === "POST") {
    const body = JSON.parse((await readBody(req, 1024)) ?? "{}");
    if (typeof body.installHash !== "string") return error(400, "installHash");
    const r = await env.DB.prepare("DELETE FROM blocked WHERE install = ?1").bind(body.installHash).run();
    return json({ ok: true, removed: r.meta.changes ?? 0 });
  }
  return error(404, "not found");
}

export default {
  async fetch(req: Request, env: Bindings): Promise<Response> {
    const url = new URL(req.url);
    if (url.pathname === "/crashhunter" || url.pathname.startsWith("/crashhunter/")) url.pathname = url.pathname.slice("/crashhunter".length) || "/";
    try {
      if (url.pathname === "/v1/health") return json({ ok: true });
      if (url.pathname === "/v1/reports" && req.method === "POST") return await postReport(req, env, Date.now());
      if (url.pathname === "/v1/runs" && req.method === "POST") return await postRun(req, env, Date.now());
      if (url.pathname === "/v1/snapshot" && req.method === "GET") return await getSnapshot(req, env, url);
      if (url.pathname === "/v1/forget" && req.method === "POST") return await forget(req, env);
      if (url.pathname === "/v1/check" && req.method === "POST") return await check(req, env, Date.now());
      if (url.pathname.startsWith("/v1/admin/")) return await admin(req, env, url);
      if (url.pathname.startsWith("/v1/verify/")) return await verify(req, env, url);
      if ((url.pathname === "/admin" || url.pathname === "/admin/") && req.method === "GET") return page(ADMIN_HTML, "text/html");
      if (url.pathname.startsWith("/admin/") && url.pathname.endsWith("/app.js") && req.method === "GET") return page(ADMIN_JS, "text/javascript");
      return error(404, "not found");
    } catch (e) {
      return error(500, "server error");
    }
  },
  async scheduled(_controller: ScheduledController, env: Bindings, ctx: ExecutionContext) {
    ctx.waitUntil(rebuild(env));
  },
} satisfies ExportedHandler<Bindings>;
