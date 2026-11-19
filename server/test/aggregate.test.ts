import { describe, expect, it } from "vitest";
import { aggregate } from "../src/aggregate";
import type { StoredItem } from "../src/types";

const a = { id: "moda", v: "1.0", sha1: "a".repeat(40) };
const b = { id: "modb", v: "2.0" };

describe("aggregate", () => {
  it("counts distinct installs and weighs bisect above diagnosis", () => {
    const items: StoredItem[] = [
      { install: "i1", item: { type: "conflict", mods: [a, b], method: "bisect", fp: "ff00ff00" } },
      { install: "i1", item: { type: "conflict", mods: [b, a], method: "bisect" } },
      { install: "i2", item: { type: "conflict", mods: [a, b], method: "diagnosis" } },
    ];
    const s = aggregate("1.20.1", "forge", items, 5);
    expect(s.conflicts).toHaveLength(1);
    expect(s.conflicts[0].n).toBe(2);
    expect(s.conflicts[0].w).toBe(1.5);
    expect(s.conflicts[0].mods.map((m) => m.id)).toEqual(["moda", "modb"]);
    expect(s.conflicts[0].mods[0].sha1).toBe("a".repeat(40));
    expect(s.generated).toBe(5);
  });

  it("compatible reports cancel conflicts", () => {
    const items: StoredItem[] = [
      { install: "i1", item: { type: "conflict", mods: [a, b], method: "bisect" } },
      { install: "i2", item: { type: "compatible", mods: [a, b, { id: "modc" }] } },
    ];
    expect(aggregate("1.20.1", "forge", items).conflicts).toHaveLength(0);
  });

  it("drops hashes that differ between reports and keeps fixes and broken files", () => {
    const items: StoredItem[] = [
      { install: "i1", item: { type: "broken", mod: { id: "emextras", v: "1.5.1", sha1: "1".repeat(40) }, reason: "NO_REFMAP" } },
      { install: "i2", item: { type: "broken", mod: { id: "emextras", v: "1.5.1", sha1: "2".repeat(40) }, reason: "NO_REFMAP" } },
      { install: "i1", item: { type: "fix", kind: "REPLACE_BROKEN", from: [{ id: "emextras", v: "1.5.1" }], to: [{ id: "emextras", v: "1.5.0-fix1" }], result: "fixed" } },
      { install: "i3", item: { type: "fix", kind: "REPLACE_BROKEN", from: [{ id: "emextras", v: "1.5.1" }], to: [{ id: "emextras", v: "1.5.0-fix1" }], result: "changed" } },
    ];
    const s = aggregate("1.20.1", "forge", items);
    expect(s.broken[0]).toMatchObject({ reason: "NO_REFMAP", n: 2, mod: { id: "emextras", v: "1.5.1" } });
    expect(s.broken[0].mod.sha1).toBeUndefined();
    expect(s.fixes[0]).toMatchObject({ kind: "REPLACE_BROKEN", n: 2, w: 1.25 });
  });
  it("failed reports from other installs cancel fixes and broken files", () => {
    const from = [{ id: "emextras", v: "1.5.1" }], to = [{ id: "emextras", v: "1.5.0-fix1" }];
    const items: StoredItem[] = [
      { install: "i1", item: { type: "fix", kind: "REPLACE_BROKEN", from, to, result: "fixed" } },
      { install: "i2", item: { type: "fix", kind: "DB_FIX", from, to, result: "failed" } },
      { install: "i1", item: { type: "broken", mod: from[0], reason: "NO_REFMAP" } },
      { install: "i3", item: { type: "fix", kind: "REPLACE_BROKEN", from, to: [], result: "failed" } },
    ];
    const s = aggregate("1.20.1", "forge", items);
    expect(s.fixes).toHaveLength(0);
    expect(s.broken).toHaveLength(0);
  });
  it("treats a missing refmap reported in a crash as a broken file", () => {
    const crash = (context: string, install: string): StoredItem => ({ install, item: { type: "crash", context, source: "log", cause: "MIXIN_NO_REFMAP", mods: [a, b], frames: [], findings: [{ cause: "MIXIN_NO_REFMAP", mod: "moda" }], sys: {}, causes: [] } as any });
    const s = aggregate("1.20.1", "forge", [crash("launch", "i1"), crash("ingame", "i1"), crash("tune", "i2")]);
    expect(s.broken).toEqual([{ mod: a, reason: "NO_REFMAP", w: 1, n: 1 }]);
  });
});
