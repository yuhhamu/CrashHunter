import { SELF, env } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import { rebuild } from "../src/index";

const H = { "content-type": "application/json", "x-ch-token": "app-token" };
const ADMIN = { authorization: "Bearer admin-token", "content-type": "application/json" };
const install = "123e4567-e89b-12d3-a456-426614174000";

function report(over: Record<string, unknown> = {}) {
  return {
    schema: 1, install, app: "1.1.0", time: 1,
    env: { mc: "1.20.1", loader: "forge", loaderVersion: "47.4.29" },
    items: [
      { type: "broken", mod: { id: "emextras", v: "1.5.1", sha1: "4".repeat(40) }, reason: "NO_REFMAP" },
      { type: "conflict", mods: [{ id: "moda", v: "1.0" }, { id: "modb", v: "2.0" }], method: "bisect", fp: "abcdef0123", ex: "MixinApplyError" },
    ],
    ...over,
  };
}

const post = (body: unknown, headers: Record<string, string> = H, ip = "1.2.3.4") =>
  SELF.fetch("https://db.example/v1/reports", { method: "POST", headers: { ...headers, "cf-connecting-ip": ip }, body: JSON.stringify(body) });

describe("api", () => {
  beforeEach(async () => {
    await env.DB.exec("DELETE FROM reports; DELETE FROM items; DELETE FROM rate; DELETE FROM blocked; DELETE FROM dirty; DELETE FROM runs; DELETE FROM blocked_ip;");
  });

  it("rejects missing tokens and invalid reports", async () => {
    expect((await post(report(), { "content-type": "application/json" })).status).toBe(401);
    expect((await post(report({ schema: 2 }))).status).toBe(400);
    expect((await post(report({ items: [{ type: "conflict", mods: [{ id: "C:\\Users\\x" }], method: "bisect" }] }))).status).toBe(400);
    expect((await post(report({ env: { mc: "1.20.1", loader: "../etc" } }))).status).toBe(400);
  });

  it("stores reports without the raw install id and builds snapshots", async () => {
    const r = await post(report());
    expect(r.status).toBe(200);
    const row = await env.DB.prepare("SELECT install, body FROM reports").first<{ install: string; body: string }>();
    expect(row!.install).not.toContain(install);
    expect(row!.body).not.toContain(install);
    expect(await rebuild(env as any)).toBe(1);
    const s = await SELF.fetch("https://db.example/v1/snapshot?mc=1.20.1&loader=forge", { headers: H });
    const snap = await s.json<any>();
    expect(snap.broken[0]).toMatchObject({ reason: "NO_REFMAP", n: 1, mod: { id: "emextras", v: "1.5.1", sha1: "4".repeat(40) } });
    expect(snap.conflicts[0].mods.map((m: any) => m.id)).toEqual(["moda", "modb"]);
    const empty = await (await SELF.fetch("https://db.example/v1/snapshot?mc=1.19.2&loader=fabric", { headers: H })).json<any>();
    expect(empty.conflicts).toEqual([]);
  });

  it("limits reports per install and per day", async () => {
    for (let i = 0; i < 5; i++) expect((await post(report())).status).toBe(200);
    expect((await post(report())).status).toBe(429);
  });

  it("forget removes everything from that install", async () => {
    await post(report());
    const f = await SELF.fetch("https://db.example/v1/forget", { method: "POST", headers: H, body: JSON.stringify({ install }) });
    expect(await f.json()).toMatchObject({ ok: true, removed: 1 });
    expect((await env.DB.prepare("SELECT COUNT(*) AS n FROM items").first<{ n: number }>())!.n).toBe(0);
  });

  it("admin can block an install and export data", async () => {
    await post(report());
    expect((await SELF.fetch("https://db.example/v1/admin/stats")).status).toBe(401);
    const b = await SELF.fetch("https://db.example/v1/admin/block", { method: "POST", headers: ADMIN, body: JSON.stringify({ install, reason: "spam" }) });
    expect(b.status).toBe(200);
    expect((await post(report())).status).toBe(403);
    const stats = await (await SELF.fetch("https://db.example/v1/admin/stats", { headers: ADMIN })).json<any>();
    expect(stats.reports).toBe(0);
    expect((await SELF.fetch("https://db.example/v1/admin/export?mc=1.20.1&loader=forge", { headers: ADMIN })).status).toBe(200);
  });
  it("stores detailed crash, dependency and large compatible items and shows them per mod", async () => {
    const crash = {
      type: "crash", context: "ingame", source: "crash-report", cause: "MOD_RUNTIME_ERROR", fp: "abcdef0123",
      ex: "java.lang.IllegalStateException", msg: "Bad at C:\\Users\\someuser\\x.json", phase: "Rendering overlay",
      causes: [{ ex: "java.lang.NullPointerException", msg: "from /home/someuser/a" }],
      frames: [{ c: "com.example.alpha.Renderer", m: "draw", src: "Renderer.java:42", mod: "alpha" }],
      mods: [{ id: "alpha", v: "1.0", sha1: "1".repeat(40), cf: 123456789 }],
      findings: [{ cause: "MIXIN_FAILURE", mod: "alpha", other: "net.minecraft.X", config: "alpha.mixins.json", line: "Mixin apply failed D:\\x" }],
      sys: { "Graphics card #0 name": "NVIDIA", "Operating System": "Windows 11" }, heapMb: 8192, seen: 3,
    };
    const dep = { type: "dependency", mod: { id: "alpha", v: "1.0" }, needs: { id: "beta", v: "2.0" }, declared: false };
    const compat = { type: "compatible", mods: Array.from({ length: 150 }, (_, i) => ({ id: `m${i}`, v: "1" })).concat([{ id: "alpha", v: "1.0" }]) };
    const r = await post(report({ env: { mc: "1.20.1", loader: "forge", os: "Windows 11", arch: "amd64" }, items: [crash, dep, compat] }));
    expect(r.status).toBe(200);
    const rows = await env.DB.prepare("SELECT data FROM items").all<{ data: string }>();
    const all = rows.results.map((x) => x.data).join("\n");
    expect(all).not.toContain("someuser");
    expect(all).toContain("<path>");
    expect((await post(report({ items: [{ ...crash, frames: [{ c: "a b", m: "x" }] }] }))).status).toBe(400);
    const view = await (await SELF.fetch("https://db.example/v1/admin/mod?id=alpha", { headers: ADMIN })).json<any>();
    expect(view.installs).toBe(1);
    expect(view.crashes[0]).toMatchObject({ cause: "MOD_RUNTIME_ERROR", installs: 1, versions: ["1.0"] });
    expect(view.dependencies[0]).toMatchObject({ needs: "beta", declared: false });
    expect(view.compatibleReports).toBe(1);
    expect(await rebuild(env as any)).toBe(1);
  });

  it("admin can block IP addresses, including an IPv6 /64, with expiry and purge", async () => {
    expect((await post(report(), H, "5.6.7.8")).status).toBe(200);
    const id = (await env.DB.prepare("SELECT id FROM reports").first<{ id: number }>())!.id;
    const b = await (await SELF.fetch("https://db.example/v1/admin/block-ip", { method: "POST", headers: ADMIN, body: JSON.stringify({ reportId: id, days: 1, purge: true, reason: "spam" }) })).json<any>();
    expect(b.removed).toBe(1);
    expect((await post(report(), H, "5.6.7.8")).status).toBe(403);
    expect((await post(report(), H, "5.6.7.9")).status).toBe(200);
    await SELF.fetch("https://db.example/v1/admin/block-ip", { method: "POST", headers: ADMIN, body: JSON.stringify({ ip: "2001:db8:1:2::1", days: 0 }) });
    expect((await post(report(), H, "2001:db8:1:2:ffff::9")).status).toBe(403);
    expect((await post(report(), H, "2001:db8:1:3::1")).status).toBe(200);
    const list = await (await SELF.fetch("https://db.example/v1/admin/blocked-ips", { headers: ADMIN })).json<any[]>();
    expect(list.length).toBe(3);
    const un = await (await SELF.fetch("https://db.example/v1/admin/unblock-ip", { method: "POST", headers: ADMIN, body: JSON.stringify({ ipHash: b.blocked[0] }) })).json<any>();
    expect(un.removed).toBe(1);
    expect((await post(report(), H, "5.6.7.8")).status).toBe(200);
  });

  it("drops an invalid exception name instead of rejecting the report", async () => {
    const r = await post(report({ items: [{ type: "conflict", mods: [{ id: "moda", v: "1.0" }, { id: "modb", v: "2.0" }], method: "bisect", ex: "cause:MOD_LOAD_ERROR" }] }));
    expect(r.status).toBe(200);
    const row = await env.DB.prepare("SELECT data FROM items").first<{ data: string }>();
    expect(row!.data).not.toContain("cause:");
  });

  it("serves the admin page with a strict policy and lets admins manage reports", async () => {
    const pageRes = await SELF.fetch("https://db.example/crashhunter/admin");
    expect(pageRes.status).toBe(200);
    expect(pageRes.headers.get("content-security-policy")).toContain("script-src 'self'");
    expect((await SELF.fetch("https://db.example/crashhunter/admin/app.js")).headers.get("content-type")).toContain("javascript");
    await post(report());
    const list = await (await SELF.fetch("https://db.example/v1/admin/reports?mod=emextras", { headers: ADMIN })).json<any[]>();
    expect(list.length).toBe(1);
    expect((await (await SELF.fetch("https://db.example/v1/admin/reports?mod=nothing", { headers: ADMIN })).json<any[]>()).length).toBe(0);
    const del = await (await SELF.fetch("https://db.example/v1/admin/delete-report", { method: "POST", headers: ADMIN, body: JSON.stringify({ id: list[0].id }) })).json<any>();
    expect(del.removed).toBe(1);
    expect((await env.DB.prepare("SELECT COUNT(*) AS n FROM items").first<{ n: number }>())!.n).toBe(0);
    await post(report());
    const row = (await (await SELF.fetch("https://db.example/v1/admin/reports", { headers: ADMIN })).json<any[]>())[0];
    await SELF.fetch("https://db.example/v1/admin/block", { method: "POST", headers: ADMIN, body: JSON.stringify({ installHash: row.install }) });
    expect((await (await SELF.fetch("https://db.example/v1/admin/blocked", { headers: ADMIN })).json<any[]>()).length).toBe(1);
    expect((await post(report())).status).toBe(403);
    await SELF.fetch("https://db.example/v1/admin/unblock", { method: "POST", headers: ADMIN, body: JSON.stringify({ installHash: row.install }) });
    expect((await post(report())).status).toBe(200);
    const stats = await (await SELF.fetch("https://db.example/v1/admin/stats", { headers: ADMIN })).json<any>();
    expect(stats.reports24h).toBe(1);
  });

  it("checks a mod list against the database without storing it", async () => {
    await post(report({ items: [
      { type: "broken", mod: { id: "emextras", v: "1.5.1" }, reason: "NO_REFMAP" },
      { type: "compatible", mods: [{ id: "a", v: "1" }, { id: "b", v: "2" }, { id: "c", v: "3" }] },
      { type: "crash", context: "launch", source: "log", cause: "MOD_LOAD_ERROR", mods: [{ id: "b", v: "2" }, { id: "a", v: "1" }], findings: [{ cause: "MOD_LOAD_ERROR", mod: "b" }] },
      { type: "crash", context: "tune", source: "log", cause: "CLASS_MISMATCH", mods: [{ id: "a", v: "1" }], findings: [{ cause: "CLASS_MISMATCH", mod: "a" }] },
    ] }));
    await rebuild(env as any);
    const before = (await env.DB.prepare("SELECT COUNT(*) AS n FROM reports").first<{ n: number }>())!.n;
    const r = await SELF.fetch("https://db.example/v1/check", { method: "POST", headers: H, body: JSON.stringify({ env: { mc: "1.20.1", loader: "forge" }, mods: [{ id: "a", v: "1" }, { id: "b", v: "2" }, { id: "emextras", v: "1.5.1" }, { id: "z", v: "9" }] }) });
    const j = await r.json<any>();
    expect(r.status).toBe(200);
    expect(j.known.broken[0].reason).toBe("NO_REFMAP");
    expect(j.compatible.reports).toBe(1);
    expect(j.compatible.supersets).toBe(0);
    expect(j.compatible.best).toBe(0.5);
    expect(j.compatible.unseen.map((m: any) => m.id).sort()).toEqual(["emextras", "z"]);
    expect(j.crashes).toHaveLength(1);
    expect(j.crashes[0]).toMatchObject({ mod: { id: "b" }, installs: 1, causes: { MOD_LOAD_ERROR: 1 } });
    expect((await env.DB.prepare("SELECT COUNT(*) AS n FROM reports").first<{ n: number }>())!.n).toBe(before);
  });

  it("accepts the /crashhunter path prefix", async () => {
    expect(await (await SELF.fetch("https://db.example/crashhunter/v1/health")).json()).toEqual({ ok: true });
  });

  it("stores run records and forget removes them", async () => {
    const run = {
      schema: 1, install, app: "1.1.0", time: 1, kind: "bisect", env: { mc: "1.20.1", loader: "forge" },
      mods: [{ id: "moda", v: "1.0" }], settings: { parallelTrials: "2" }, status: "FOUND", durationMs: 1000, parallel: 2, estimate: 7,
      trials: [{ n: 1, slot: 0, phase: "baseline", enabled: 10, pool: 10, result: "FAIL", ms: 60000 }],
    };
    const send = (b: unknown) => SELF.fetch("https://db.example/v1/runs", { method: "POST", headers: { ...H, "cf-connecting-ip": "1.2.3.4" }, body: JSON.stringify(b) });
    expect((await send({ ...run, kind: "other" })).status).toBe(400);
    expect((await send({ ...run, env: { mc: "1.20.1", loader: "../x" } })).status).toBe(400);
    expect((await send(run)).status).toBe(200);
    const row = await env.DB.prepare("SELECT install, body FROM runs").first<{ install: string; body: string }>();
    expect(row!.install).not.toContain(install);
    expect(row!.body).not.toContain(install);
    const f = await SELF.fetch("https://db.example/v1/forget", { method: "POST", headers: H, body: JSON.stringify({ install }) });
    expect((await f.json<any>()).removed).toBe(1);
    expect((await env.DB.prepare("SELECT COUNT(*) AS n FROM runs").first<{ n: number }>())!.n).toBe(0);
  });
});
