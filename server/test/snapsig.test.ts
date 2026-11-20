import { env, SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";

const H = { "x-ch-token": "app-token" };

describe("signed snapshot", () => {
  it("returns data with a valid Ed25519 signature", async () => {
    const r = await SELF.fetch("https://db.example/v1/snapshot?mc=1.20.1&loader=forge&signed=1", { headers: H });
    expect(r.status).toBe(200);
    const b = await r.json<any>();
    const snap = JSON.parse(b.data);
    expect(snap.mc).toBe("1.20.1");
    const key = await crypto.subtle.importKey("spki", Uint8Array.from(atob((env as any).SNAP_TEST_PUB), (c) => c.charCodeAt(0)), { name: "Ed25519" }, false, ["verify"]);
    const sig = Uint8Array.from(atob(b.sig), (c) => c.charCodeAt(0));
    expect(await crypto.subtle.verify({ name: "Ed25519" }, key, sig, new TextEncoder().encode(b.data))).toBe(true);
    expect(await crypto.subtle.verify({ name: "Ed25519" }, key, sig, new TextEncoder().encode(b.data + " "))).toBe(false);
  });
});

describe("scrub", () => {
  it("hides paths with spaces, network paths and ids but keeps urls", async () => {
    const { scrub } = await import("../src/validate");
    const s = scrub("C:\\Users\\John Smith\\x.jar \\\\nas\\Share Folder\\y --username Steve 10.0.0.1 https://a.com/b", 500);
    expect(s).not.toMatch(/John|Smith|nas|Share|Steve|10\.0\.0\.1/);
    expect(s).toContain("https://a.com/b");
  });
});
