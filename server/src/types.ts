export interface ModRef { id: string; v?: string; sha1?: string; cf?: number }
export interface Env { mc: string; loader: string; loaderVersion?: string; java?: number; os?: string; arch?: string }

export type Item =
  | { type: "conflict"; mods: ModRef[]; fp?: string; ex?: string; method: "bisect" | "diagnosis"; trials?: number }
  | { type: "fix"; kind: string; from: ModRef[]; to: ModRef[]; result: "fixed" | "changed" | "failed"; fp?: string }
  | { type: "broken"; mod: ModRef; reason: string }
  | { type: "compatible"; mods: ModRef[] }
  | CrashItem
  | { type: "dependency"; mod: ModRef; needs: ModRef; declared: boolean; range?: string };

export interface ExRef { ex: string; msg?: string }
export interface FrameRef { c: string; m: string; src?: string; mod?: string }
export interface FindingRef { cause: string; mod?: string; other?: string; required?: string; actual?: string; config?: string; line?: string }
export interface CrashItem {
  type: "crash"; context: "launch" | "ingame" | "tune"; source: "crash-report" | "hs_err" | "log"; cause: string;
  fp?: string; ex?: string; msg?: string; phase?: string; causes: ExRef[]; frames: FrameRef[]; mods: ModRef[];
  findings: FindingRef[]; sys: Record<string, string>; heapMb?: number; seen?: number;
}

export interface Report { schema: 1; install: string; app: string; time: number; env: Env; items: Item[] }

export interface StoredItem { install: string; item: Item }

export interface SnapConflict { mods: ModRef[]; fp?: string; ex?: string; w: number; n: number }
export interface SnapFix { kind: string; from: ModRef[]; to: ModRef[]; fp?: string; w: number; n: number }
export interface SnapBroken { mod: ModRef; reason: string; w: number; n: number; confirmed?: boolean }
export interface Verdict { id: string; v: string; sha1?: string; reason: string; verdict: string; method: string; detail?: string; by: string; tool?: string; created: number }
export interface Snapshot { schema: 1; mc: string; loader: string; generated: number; conflicts: SnapConflict[]; fixes: SnapFix[]; broken: SnapBroken[] }
