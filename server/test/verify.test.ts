import { SELF, env } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import worker, { rebuild } from "../src/index";

const H = { "content-type": "application/json", "x-ch-token": "app-token" };
const TEAM = "team.cloudflareaccess.com";
const AUD = "aud-tag";
const enc = (o: unknown) => btoa(typeof o === "string" ? o : JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

async function keys() {
  const pair = (await crypto.subtle.generateKey({ name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"])) as CryptoKeyPair;
  const pub = (await crypto.subtle.exportKey("jwk", pair.publicKey)) as JsonWebKey;
  return { pair, jwks: JSON.stringify({ keys: [{ ...pub, kid: "k1", alg: "RS256" }] }) };
}

async function jwt(pair: CryptoKeyPair, payload: Record<string, unknown>) {
  const head = enc({ alg: "RS256", kid: "k1" }) + "." + enc(payload);
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", pair.privateKey, new TextEncoder().encode(head)));
  return head + "." + enc(String.fromCharCode(...sig));
}

const crash = {
  schema: 1, install: "123e4567-e89b-12d3-a456-426614174000", app: "1.2.0", time: 1, env: { mc: "1.20.1", loader: "forge" },
  items: [{ type: "crash", context: "launch", source: "log", cause: "MIXIN_NO_REFMAP", mods: [{ id: "emextras", v: "1.5.1", sha1: "4".repeat(40) }], findings: [{ cause: "MIXIN_NO_REFMAP", mod: "emextras" }] }],
};

describe("verify", () => {
  beforeEach(async () => {
    await env.DB.exec("DELETE FROM reports; DELETE FROM items; DELETE FROM rate; DELETE FROM dirty; DELETE FROM verdicts;");
  });

  it("is closed unless Cloudflare Access is configured", async () => {
    expect((await SELF.fetch("https://db.example/v1/verify/pending")).status).toBe(503);
  });

  it("accepts verdicts only with a valid Access token and applies them to the snapshot", async () => {
    const { pair, jwks } = await keys();
    const e = { ...env, ACCESS_TEAM: TEAM, ACCESS_AUD: AUD, ACCESS_JWKS: jwks } as any;
    const call = (path: string, token?: string, body?: unknown) =>
      worker.fetch(new Request(`https://db.example/crashhunter/v1/verify/${path}`, { method: body ? "POST" : "GET", headers: { "content-type": "application/json", ...(token ? { "cf-access-jwt-assertion": token } : {}) }, body: body ? JSON.stringify(body) : undefined }), e);
    const exp = Math.floor(Date.now() / 1000) + 600;
    const good = await jwt(pair, { aud: [AUD], iss: `https://${TEAM}`, exp, email: "me@example.com" });
    const wrongAud = await jwt(pair, { aud: ["other"], iss: `https://${TEAM}`, exp, email: "me@example.com" });
    const expired = await jwt(pair, { aud: [AUD], iss: `https://${TEAM}`, exp: 1, email: "me@example.com" });

    expect((await SELF.fetch("https://db.example/v1/reports", { method: "POST", headers: H, body: JSON.stringify(crash) })).status).toBe(200);
    await rebuild(env as any);
    expect((await call("pending")).status).toBe(401);
    expect((await call("pending", wrongAud)).status).toBe(401);
    expect((await call("pending", expired)).status).toBe(401);
    expect((await call("pending", good.slice(0, -4) + "AAAA")).status).toBe(401);
    expect(await (await call("whoami", good)).json()).toEqual({ who: "me@example.com" });
    const list = (await (await call("pending", good)).json()) as any[];
    expect(list).toHaveLength(1);
    expect(list[0]).toMatchObject({ mod: { id: "emextras", v: "1.5.1" }, reason: "NO_REFMAP", envs: ["1.20.1/forge"] });

    expect((await call("result", good, { mod: { id: "emextras", v: "1.5.1" }, reason: "NO_REFMAP", verdict: "maybe", method: "static" })).status).toBe(400);
    expect((await call("result", good, { mod: { id: "emextras", v: "1.5.1" }, reason: "NO_REFMAP", verdict: "confirmed", method: "static", detail: "C:\\Users\\x\\a.jar has no refmap" })).status).toBe(200);
    expect(await (await call("pending", good)).json()).toEqual([]);
    const snap = JSON.parse((await env.SNAP.get("snap/1.20.1/forge"))!);
    expect(snap.broken[0]).toMatchObject({ mod: { id: "emextras", v: "1.5.1" }, confirmed: true });
    const v = await env.DB.prepare("SELECT by, detail FROM verdicts").first<any>();
    expect(v).toEqual({ by: "me@example.com", detail: "<path> has no refmap" });

    expect((await call("result", good, { mod: { id: "emextras", v: "1.5.1" }, reason: "NO_REFMAP", verdict: "rejected", method: "manual" })).status).toBe(200);
    expect(JSON.parse((await env.SNAP.get("snap/1.20.1/forge"))!).broken).toEqual([]);
  });
});
