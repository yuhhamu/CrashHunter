import type { ModRef, SnapBroken, SnapConflict, SnapFix, Snapshot, StoredItem, Verdict } from "./types";

const refKey = (r: ModRef) => `${r.id}@${r.v ?? ""}`;
const setKey = (rs: ModRef[]) => rs.map(refKey).sort().join("|");

function mergeRef(rs: ModRef[]): ModRef {
  const first = rs[0];
  const hashes = new Set(rs.map((r) => r.sha1).filter(Boolean));
  const out: ModRef = { id: first.id };
  if (first.v) out.v = first.v;
  if (hashes.size === 1 && rs.every((r) => r.sha1)) out.sha1 = [...hashes][0] as string;
  const cfs = new Set(rs.map((r) => r.cf).filter((x) => x !== undefined));
  if (cfs.size === 1 && rs.every((r) => r.cf !== undefined)) out.cf = [...cfs][0] as number;
  return out;
}

function mergeRefs(groups: ModRef[][]): ModRef[] {
  const byKey = new Map<string, ModRef[]>();
  for (const g of groups) for (const r of g) { const k = refKey(r); byKey.set(k, [...(byKey.get(k) ?? []), r]) }
  return [...byKey.keys()].sort().map((k) => mergeRef(byKey.get(k)!));
}

const CRASH_BROKEN: Record<string, string> = { MIXIN_NO_REFMAP: "NO_REFMAP", CORRUPT_JAR: "BROKEN" };

function round(x: number) { return Math.round(x * 100) / 100 }

export function aggregate(mc: string, loader: string, items: StoredItem[], now = Date.now(), verdicts: Verdict[] = []): Snapshot {
  const conflicts = new Map<string, { refs: ModRef[][]; installs: Map<string, number>; fp?: string; ex?: string }>();
  const fixes = new Map<string, { kind: string; from: ModRef[][]; to: ModRef[][]; fixed: Set<string>; changed: Set<string>; fp?: string }>();
  const broken = new Map<string, { refs: ModRef[]; reason: string; installs: Set<string> }>();
  const compatible: { install: string; keys: Set<string> }[] = [];
  const failedFix = new Map<string, Set<string>>();
  const failedBroken = new Map<string, Set<string>>();

  for (const { install, item } of items) {
    if (item.type === "conflict") {
      const k = setKey(item.mods);
      const e = conflicts.get(k) ?? { refs: [] as ModRef[][], installs: new Map<string, number>(), fp: item.fp, ex: item.ex };
      e.refs.push(item.mods);
      const s = item.method === "bisect" ? 1 : 0.5;
      e.installs.set(install, Math.max(e.installs.get(install) ?? 0, s));
      e.fp ??= item.fp; e.ex ??= item.ex;
      conflicts.set(k, e);
    } else if (item.type === "fix" && item.result === "failed") {
      const fk = `${setKey(item.from)}>${setKey(item.to)}`;
      failedFix.set(fk, new Set([...(failedFix.get(fk) ?? []), install]));
      if (item.to.length === 0) for (const r of item.from) failedBroken.set(refKey(r), new Set([...(failedBroken.get(refKey(r)) ?? []), install]));
    } else if (item.type === "fix") {
      const k = `${item.kind}#${setKey(item.from)}>${setKey(item.to)}`;
      const e = fixes.get(k) ?? { kind: item.kind, from: [] as ModRef[][], to: [] as ModRef[][], fixed: new Set<string>(), changed: new Set<string>(), fp: item.fp };
      e.from.push(item.from); e.to.push(item.to);
      (item.result === "fixed" ? e.fixed : e.changed).add(install);
      fixes.set(k, e);
    } else if (item.type === "broken") {
      const k = `${refKey(item.mod)}#${item.reason}`;
      const e = broken.get(k) ?? { refs: [] as ModRef[], reason: item.reason, installs: new Set<string>() };
      e.refs.push(item.mod); e.installs.add(install);
      broken.set(k, e);
    } else if (item.type === "crash" && item.context !== "tune" && CRASH_BROKEN[item.cause]) {
      const reason = CRASH_BROKEN[item.cause];
      for (const f of item.findings ?? []) {
        if (f.cause !== item.cause || !f.mod) continue;
        const ref = item.mods.find((m) => m.id === f.mod);
        if (!ref) continue;
        const k = `${refKey(ref)}#${reason}`;
        const e = broken.get(k) ?? { refs: [] as ModRef[], reason, installs: new Set<string>() };
        e.refs.push(ref); e.installs.add(install);
        broken.set(k, e);
      }
    } else if (item.type === "compatible") {
      compatible.push({ install, keys: new Set(item.mods.map(refKey)) });
    }
  }

  const snapConflicts: SnapConflict[] = [];
  for (const [k, e] of conflicts) {
    const keys = k.split("|");
    const against = new Set(compatible.filter((c) => keys.every((x) => c.keys.has(x))).map((c) => c.install));
    const w = [...e.installs.values()].reduce((a, b) => a + b, 0) - against.size;
    if (w <= 0) continue;
    const c: SnapConflict = { mods: mergeRefs(e.refs), w: round(w), n: e.installs.size };
    if (e.fp) c.fp = e.fp;
    if (e.ex) c.ex = e.ex;
    snapConflicts.push(c);
  }

  const snapFixes: SnapFix[] = [];
  for (const e of fixes.values()) {
    const fk = `${setKey(e.from[0])}>${setKey(e.to[0])}`;
    const against = [...(failedFix.get(fk) ?? [])].filter((i) => !e.fixed.has(i)).length;
    const w = e.fixed.size + 0.25 * [...e.changed].filter((i) => !e.fixed.has(i)).length - against;
    if (w <= 0) continue;
    const f: SnapFix = { kind: e.kind, from: mergeRefs(e.from), to: mergeRefs(e.to), w: round(w), n: new Set([...e.fixed, ...e.changed]).size };
    if (e.fp) f.fp = e.fp;
    snapFixes.push(f);
  }

  const decided = new Map(verdicts.filter((v) => v.verdict !== "inconclusive").map((v) => [`${v.id}@${v.v}#${v.reason}`, v.verdict]));
  const snapBroken: SnapBroken[] = [];
  for (const e of broken.values()) {
    const mod = mergeRef(e.refs);
    const verdict = decided.get(`${mod.id}@${mod.v ?? ""}#${e.reason}`);
    if (verdict === "rejected") continue;
    const against = [...(failedBroken.get(refKey(e.refs[0])) ?? [])].filter((i) => !e.installs.has(i)).length;
    const b: SnapBroken = { mod, reason: e.reason, w: e.installs.size - against, n: e.installs.size };
    if (verdict === "confirmed") { b.confirmed = true; b.w = Math.max(b.w, e.installs.size) + 10; }
    if (b.w > 0) snapBroken.push(b);
  }

  const byW = <T extends { w: number }>(a: T, b: T) => b.w - a.w;
  return {
    schema: 1, mc, loader, generated: now,
    conflicts: snapConflicts.sort(byW).slice(0, 20000),
    fixes: snapFixes.sort(byW).slice(0, 20000),
    broken: snapBroken.sort(byW).slice(0, 20000),
  };
}
