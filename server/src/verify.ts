import type { ModRef, Snapshot, Verdict } from "./types";

export const VERDICTS = new Set(["confirmed", "rejected", "inconclusive"]);
export const METHODS = new Set(["static", "launch", "manual"]);
export const REASONS = new Set(["NO_REFMAP", "BROKEN", "CRASH"]);
const ID = /^[a-z0-9_.\-]{1,64}$/;
const VERSION = /^[A-Za-z0-9_.+\-]{1,64}$/;
const SHA1 = /^[0-9a-f]{40}$/;

export interface VerdictInput { mod: ModRef; reason: string; verdict: string; method: string; detail?: string; tool?: string }

export function parseVerdict(body: any): VerdictInput {
  const m = body?.mod;
  if (!m || typeof m.id !== "string" || !ID.test(m.id.toLowerCase())) throw new Error("mod");
  if (typeof m.v !== "string" || !VERSION.test(m.v)) throw new Error("version");
  if (m.sha1 !== undefined && (typeof m.sha1 !== "string" || !SHA1.test(m.sha1))) throw new Error("sha1");
  if (!REASONS.has(body.reason)) throw new Error("reason");
  if (!VERDICTS.has(body.verdict)) throw new Error("verdict");
  if (!METHODS.has(body.method)) throw new Error("method");
  const detail = typeof body.detail === "string" ? body.detail.replace(/[A-Za-z]:\\[^\s"']*/g, "<path>").slice(0, 500) : undefined;
  const tool = typeof body.tool === "string" ? body.tool.slice(0, 40) : undefined;
  return { mod: { id: m.id.toLowerCase(), v: m.v, sha1: m.sha1 }, reason: body.reason, verdict: body.verdict, method: body.method, detail, tool };
}

export async function latestVerdicts(db: D1Database): Promise<Verdict[]> {
  const rows = await db.prepare(
    "SELECT v.mod_id, v.version, v.sha1, v.reason, v.verdict, v.method, v.detail, v.by, v.tool, v.created FROM verdicts v JOIN (SELECT mod_id, version, reason, MAX(id) AS id FROM verdicts GROUP BY mod_id, version, reason) l ON l.id = v.id ORDER BY v.id DESC LIMIT 20000",
  ).all<any>();
  return rows.results.map((r) => ({ id: r.mod_id, v: r.version, sha1: r.sha1 ?? undefined, reason: r.reason, verdict: r.verdict, method: r.method, detail: r.detail ?? undefined, by: r.by, tool: r.tool ?? undefined, created: r.created }));
}

export async function pending(snap: KVNamespace, verdicts: Verdict[]): Promise<any[]> {
  const decided = new Set(verdicts.filter((v) => v.verdict !== "inconclusive").map((v) => `${v.id}@${v.v}#${v.reason}`));
  const tried = new Map(verdicts.filter((v) => v.verdict === "inconclusive").map((v) => [`${v.id}@${v.v}#${v.reason}`, v.created]));
  const out = new Map<string, any>();
  let cursor: string | undefined;
  do {
    const page = await snap.list({ prefix: "snap/", cursor });
    for (const k of page.keys) {
      const s = JSON.parse((await snap.get(k.name)) ?? "null") as Snapshot | null;
      if (!s) continue;
      for (const b of s.broken) {
        if (!b.mod.v) continue;
        const key = `${b.mod.id}@${b.mod.v}#${b.reason}`;
        if (decided.has(key)) continue;
        const e = out.get(key) ?? { mod: b.mod, reason: b.reason, n: 0, envs: [] as string[], tried: tried.get(key) };
        e.n += b.n;
        e.envs.push(`${s.mc}/${s.loader}`);
        if (!e.mod.sha1 && b.mod.sha1) e.mod = b.mod;
        out.set(key, e);
      }
    }
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);
  return [...out.values()].sort((a, b) => b.n - a.n);
}

export async function saveVerdict(db: D1Database, v: VerdictInput, by: string, now: number) {
  await db.prepare("INSERT INTO verdicts (mod_id, version, sha1, reason, verdict, method, detail, by, tool, created) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)")
    .bind(v.mod.id, v.mod.v ?? "", v.mod.sha1 ?? null, v.reason, v.verdict, v.method, v.detail ?? null, by.slice(0, 120), v.tool ?? null, now).run();
  await db.prepare("INSERT OR IGNORE INTO dirty (mc, loader) SELECT DISTINCT mc, loader FROM items").run();
}

const b64url = (s: string) => Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(s.length / 4) * 4, "=")), (c) => c.charCodeAt(0));
let certCache: { at: number; keys: any[] } | null = null;

async function certs(team: string, fixed?: string): Promise<any[]> {
  if (fixed) return JSON.parse(fixed).keys ?? [];
  if (certCache && Date.now() - certCache.at < 3600_000) return certCache.keys;
  const r = await fetch(`https://${team}/cdn-cgi/access/certs`);
  if (!r.ok) throw new Error("certs");
  const keys = ((await r.json()) as any).keys ?? [];
  certCache = { at: Date.now(), keys };
  return keys;
}

export async function accessIdentity(req: Request, team: string | undefined, aud: string | undefined, jwks?: string, now = Date.now()): Promise<string | null> {
  if (!team || !aud) return null;
  const token = req.headers.get("cf-access-jwt-assertion");
  if (!token) return null;
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const header = JSON.parse(new TextDecoder().decode(b64url(parts[0])));
    const payload = JSON.parse(new TextDecoder().decode(b64url(parts[1])));
    if (header.alg !== "RS256") return null;
    const jwk = (await certs(team, jwks)).find((k) => k.kid === header.kid);
    if (!jwk) return null;
    const key = await crypto.subtle.importKey("jwk", jwk, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
    const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, b64url(parts[2]), new TextEncoder().encode(`${parts[0]}.${parts[1]}`));
    if (!ok) return null;
    const auds: string[] = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
    if (!auds.includes(aud)) return null;
    if (typeof payload.exp !== "number" || payload.exp * 1000 < now) return null;
    if (payload.iss !== `https://${team}`) return null;
    const who = payload.email ?? payload.common_name ?? payload.sub;
    return typeof who === "string" && who ? who : null;
  } catch {
    return null;
  }
}
