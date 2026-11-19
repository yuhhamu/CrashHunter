(function () {
  "use strict";
  var root = document.getElementById("app");
  var local = location.hostname === "127.0.0.1" || location.hostname === "localhost";
  var base = location.pathname.replace(/\/admin\/?.*$/, "");
  var KEY = "chAdminToken";
  function token() { return sessionStorage.getItem(KEY) || ""; }
  var page = "overview";

  function h(tag, attrs) {
    var el = document.createElement(tag);
    var a = attrs || {};
    Object.keys(a).forEach(function (k) {
      var v = a[k];
      if (v === null || v === undefined || v === false) return;
      if (k === "onclick" || k === "onchange" || k === "onkeydown") el[k] = v;
      else if (k === "class") el.className = v;
      else if (k === "style") el.style.cssText = v;
      else el.setAttribute(k, v === true ? "" : String(v));
    });
    for (var i = 2; i < arguments.length; i++) add(el, arguments[i]);
    return el;
  }
  function add(el, c) {
    if (c === null || c === undefined || c === false) return;
    if (Array.isArray(c)) { c.forEach(function (x) { add(el, x); }); return; }
    el.appendChild(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); return el; }
  function time(ms) {
    if (!ms) return "-";
    var d = new Date(ms);
    function p(n) { return (n < 10 ? "0" : "") + n; }
    return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate()) + " " + p(d.getHours()) + ":" + p(d.getMinutes());
  }
  function short(s) { return s ? String(s).slice(0, 10) : "-"; }
  function toast(text, bad) {
    var t = h("div", { class: "toast" + (bad ? " err" : "") }, text);
    document.body.appendChild(t);
    setTimeout(function () { t.remove(); }, 3000);
  }
  function api(path, opts) {
    var o = opts || {};
    var init = { method: o.body ? "POST" : "GET", headers: local ? {} : { authorization: "Bearer " + token() } };
    if (o.body) { init.headers["content-type"] = "application/json"; init.body = JSON.stringify(o.body); }
    return fetch(local ? "/api/" + path : base + "/v1/admin/" + path, init).then(function (r) {
      if (r.status === 401) {
        if (local) throw new Error("管理用トークンが正しくありません(.env を確認してください)");
        sessionStorage.removeItem(KEY); render(); throw new Error("認証に失敗しました");
      }
      return r.json().then(function (j) { if (!r.ok) throw new Error(j && j.error ? j.error : "HTTP " + r.status); return j; });
    });
  }
  function fail(e) { toast(e.message || String(e), true); }
  function parse(s) { try { return JSON.parse(s); } catch (e) { return null; } }
  function pretty(v) { return JSON.stringify(v, null, 2); }
  function table(cols, rows, onRow) {
    if (!rows.length) return h("div", { class: "empty" }, "なし");
    return h("table", null,
      h("thead", null, h("tr", null, cols.map(function (c) { return h("th", null, c); }))),
      h("tbody", null, rows.map(function (r, i) {
        return h("tr", { class: onRow ? "click" : null, onclick: onRow ? function () { onRow(i); } : null },
          r.map(function (c) { return h("td", null, c); }));
      })));
  }
  function confirmDo(text, fn) { if (window.confirm(text)) fn(); }
  function dot(color) { return h("span", { class: "dot", style: "background:" + color }); }

  var CAUSE = {
    MISSING_DEPENDENCY: "前提Modが入っていない", DEPENDENCY_VERSION: "前提Modのバージョンが合わない", LOADER_VERSION: "ローダーが古い",
    MC_VERSION: "Minecraftのバージョン違い", WRONG_LOADER: "別ローダー用のMod", DUPLICATE_MOD: "Modの重複", JAVA_VERSION: "Javaのバージョン違い",
    OUT_OF_MEMORY: "メモリ不足", MIXIN_NO_REFMAP: "Mixinのrefmapがない", MIXIN_FAILURE: "Mixinの衝突", CORRUPT_JAR: "jarが壊れている",
    CLASS_MISMATCH: "Mod間のバージョン不整合(クラスがない)", CLIENT_ONLY_ON_SERVER: "クライアント専用Modがサーバーにある", CONFIG_ERROR: "設定ファイルの破損",
    GPU_DRIVER: "グラフィックドライバー", NATIVE_CRASH: "Java本体の異常終了", WATCHDOG_HANG: "フリーズ", INCOMPATIBLE_MOD: "一緒に使えないMod",
    MOD_LOAD_ERROR: "Modの読み込み中のエラー", MOD_RUNTIME_ERROR: "Modの処理中のエラー", NOT_A_MOD: "Mod以外が原因", UNKNOWN: "原因不明",
    KNOWN_BROKEN: "既知の壊れたMod", KNOWN_CONFLICT: "既知の競合", KNOWN_FIX: "既知の修正", CRASH_TOOL: "クラッシュを扱う別のMod"
  };
  var FIX = {
    DUPLICATE: "重複Modの無効化", WRONG_LOADER: "別ローダー用Modの無効化", MC_MISMATCH: "MCバージョン違いの無効化", CORRUPT_JAR: "壊れたjarの無効化",
    ENABLE_PROVIDER: "無効だったModの有効化", CULPRIT: "原因Modの無効化", MEMORY: "メモリ上限の変更", JAVA: "Javaの切り替え",
    ADD_DEPENDENCY: "前提Modの追加", UPDATE_DEPENDENCY: "前提Modの更新", REPLACE_VERSION: "バージョンの置き換え", REPLACE_BROKEN: "壊れたModの入れ替え",
    DB_FIX: "データベースの修正", LOADER_UPDATE: "ローダーの更新", CONFIG_RESET: "設定の作り直し", TUNE_VERSION: "構成調整でのバージョン変更", CRASH_TOOL: "クラッシュを扱うModの無効化"
  };
  var RESULT = { fixed: "直った", changed: "別のエラーに変わった", failed: "直らなかった" };
  var REASON = { NO_REFMAP: "refmapがない", BROKEN: "壊れている", CRASH: "単体で落ちる" };
  var CONTEXT = { launch: "起動確認中", ingame: "プレイ中", tune: "構成調整中(変更後の構成)" };
  var SOURCE = { "crash-report": "クラッシュレポート", hs_err: "Java異常終了(hs_err)", log: "ログ" };
  var KIND = { bisect: "原因特定", tune: "構成調整" };
  var STATUS = {
    FOUND: "原因を特定", NO_FAILURE_AT_BASELINE: "最初から起動できた", NOT_A_MOD: "Mod以外が原因", START_FAILED: "起動できなかった",
    INCONCLUSIVE: "特定できなかった", CANCELLED: "中止", ERROR: "エラー", LAUNCHED: "起動できる構成を発見", ENVIRONMENT: "環境の問題",
    BLOCKED: "固定のため調整できない", LIMIT: "試行の上限に到達"
  };
  var TRIAL = { PASS: "起動成功", FAIL: "クラッシュ", HANG: "フリーズ", START_FAILED: "起動失敗", CANCELLED: "中止" };
  var PHASE = { baseline: "全Mod", search: "探索", verify: "確認", "tune-launch": "構成の起動" };
  var STEP = { version: "バージョン変更", noVersions: "候補なし", loader: "ローダー変更", remove: "除外" };
  var SETTING = {
    parallelTrials: "並列数", maxParallel: "並列の上限", repeats: "繰り返し", settleSec: "起動後の待機(秒)", startTimeoutSec: "起動の制限(秒)",
    timeoutSec: "全体の制限(秒)", anyCrash: "どのクラッシュも失敗扱い", hangIsFailure: "フリーズを失敗扱い", minimizeGames: "ゲーム画面の最小化",
    workCopy: "作業コピー", dbUse: "データベースの利用", algorithm: "探し方", rules: "区分の件数"
  };
  var SYS = {
    "Operating System": "OS", "Java Version": "Java", "Java VM Version": "Java VM", Memory: "メモリ", CPUs: "CPU数", "Processor Name": "CPU",
    "Graphics card #0 name": "GPU", "Graphics card #0 vendor": "GPUメーカー", "Graphics card #0 versionInfo": "GPUドライバー",
    "Backend API": "描画API", "Backend library": "描画ライブラリ", "Launched Version": "起動バージョン", Type: "種類", "Is Modded": "Mod",
    "Flywheel Backend": "Flywheel", "Render Distance": "描画距離"
  };
  var ITEM = { conflict: "競合", fix: "修正", broken: "壊れたファイル", dependency: "前提Mod", compatible: "起動できた構成", crash: "クラッシュ" };
  function label(map, key) { return key == null ? "-" : (map[key] || key); }
  function fixKind(k) { return String(k || "").split("+").map(function (x) { return label(FIX, x); }).join("・"); }
  function yesNo(v) { return v === "true" ? "はい" : v === "false" ? "いいえ" : v; }
  function duration(ms) {
    if (ms == null) return "-";
    var s = Math.round(ms / 1000);
    if (s < 60) return s + "秒";
    var m = Math.floor(s / 60);
    return m < 60 ? m + "分" + (s % 60) + "秒" : Math.floor(m / 60) + "時間" + (m % 60) + "分";
  }
  function exName(ex) { if (!ex) return "-"; var i = ex.lastIndexOf("."); return h("span", { title: ex }, i >= 0 ? ex.slice(i + 1) : ex); }
  function envText(e) {
    if (!e) return "-";
    return ["Minecraft " + e.mc, (e.loader || "") + (e.loaderVersion ? " " + e.loaderVersion : ""), e.java ? "Java " + e.java : null, e.os, e.arch].filter(Boolean).join(" / ");
  }
  function kv(rows) {
    return h("table", { style: "width:auto" }, h("tbody", null, rows.filter(function (r) { return r && r[1] !== undefined && r[1] !== null && r[1] !== ""; })
      .map(function (r) { return h("tr", null, h("th", { style: "white-space:nowrap" }, r[0]), h("td", null, r[1])); })));
  }
  function section(title, body) { return [h("h2", null, title), body]; }

  function login() {
    var input = h("input", { type: "password", placeholder: "管理用トークン", autocomplete: "off", style: "width:100%;margin:10px 0" });
    var go = function () {
      if (!input.value.trim()) return;
      sessionStorage.setItem(KEY, input.value.trim());
      api("stats").then(function () { render(); }).catch(fail);
    };
    input.onkeydown = function (e) { if (e.key === "Enter") go(); };
    clear(root).appendChild(h("div", { style: "max-width:360px;margin:18vh auto;background:var(--panel);padding:20px;border-radius:10px" },
      h("h1", null, "CrashHunter Database"), input, h("button", { class: "b p", onclick: go }, "ログイン")));
    input.focus();
  }

  var pages = [
    ["overview", "概要"], ["reports", "報告"], ["runs", "実行記録"], ["mod", "Mod"], ["verify", "検証"], ["blocks", "遮断"],
  ];

  function render() {
    if (!local && !token()) { login(); return; }
    var main = h("main");
    var nav = h("nav", null, h("div", { class: "brand" }, "CrashHunter DB"),
      pages.map(function (p) {
        return h("button", { class: page === p[0] ? "on" : null, onclick: function () { page = p[0]; render(); } }, p[1]);
      }),
      h("div", { class: "grow" }),
      local ? h("div", { class: "muted", style: "padding:0 10px;font-size:12px" }, "ローカル")
        : h("button", { onclick: function () { sessionStorage.removeItem(KEY); render(); } }, "ログアウト"));
    clear(root).appendChild(h("div", { id: "app-inner", style: "display:flex;width:100%" }, nav, main));
    ({ overview: overview, reports: reports, runs: runs, mod: modPage, verify: verifyPage, blocks: blocks })[page](main);
  }

  function overview(main) {
    main.appendChild(h("h1", null, "概要"));
    var cards = h("div", { class: "cards" });
    main.appendChild(cards);
    api("stats").then(function (s) {
      [["報告", s.reports], ["24時間の報告", s.reports24h], ["項目", s.items], ["端末", s.installs], ["実行記録", s.runs], ["遮断中の端末", s.blocked], ["遮断中のIP", s.blockedIps]]
        .forEach(function (c) { cards.appendChild(h("div", { class: "card" }, h("div", { class: "n" }, c[1] == null ? "-" : c[1]), h("div", { class: "l" }, c[0]))); });
    }).catch(fail);

    main.appendChild(h("h2", null, "配布データ"));
    var mc = h("input", { value: "1.20.1", size: 8 });
    var loader = h("select", null, ["forge", "neoforge", "fabric", "quilt", "forge_legacy"].map(function (l) { return h("option", { value: l }, l); }));
    var out = h("div");
    var show = function () {
      clear(out).appendChild(h("div", { class: "muted" }, "読み込み中…"));
      api("export?mc=" + encodeURIComponent(mc.value.trim()) + "&loader=" + loader.value).then(function (snap) {
        clear(out);
        out.appendChild(h("div", { class: "muted" }, "作成: " + time(snap.generated)));
        var ref = function (m) { return m.id + (m.v ? " " + m.v : ""); };
        out.appendChild(h("h2", null, "競合 " + snap.conflicts.length));
        out.appendChild(table(["Mod", "重み", "台数", "例外"], snap.conflicts.slice(0, 100).map(function (c) { return [c.mods.map(ref).join(" + "), c.w, c.n, c.ex || "-"]; })));
        out.appendChild(h("h2", null, "対処法 " + snap.fixes.length));
        out.appendChild(table(["種類", "変更前", "変更後", "重み", "台数"], snap.fixes.slice(0, 100).map(function (f) { return [fixKind(f.kind), f.from.map(ref).join(", "), f.to.map(ref).join(", "), f.w, f.n]; })));
        out.appendChild(h("h2", null, "壊れたファイル " + snap.broken.length));
        out.appendChild(table(["Mod", "理由", "重み", "台数"], snap.broken.slice(0, 100).map(function (b) { return [ref(b.mod), label(REASON, b.reason), b.w, b.n]; })));
      }).catch(fail);
    };
    main.appendChild(h("div", { class: "row" }, mc, loader,
      h("button", { class: "b", onclick: show }, "表示"),
      h("button", { class: "b", onclick: function () { api("rebuild", { body: {} }).then(function (r) { toast("再集計 " + r.rebuilt + "件"); show(); }).catch(fail); } }, "再集計")));
    main.appendChild(out);
    show();
  }

  function itemSummary(body) {
    var b = parse(body);
    if (!b || !b.items) return "-";
    var n = {};
    b.items.forEach(function (it) { n[it.type] = (n[it.type] || 0) + 1; });
    return Object.keys(n).map(function (k) { return label(ITEM, k) + (n[k] > 1 ? " " + n[k] : ""); }).join("・");
  }

  function reports(main) {
    main.appendChild(h("h1", null, "報告"));
    var filter = h("input", { placeholder: "Mod ID で絞り込み", size: 24 });
    var list = h("div");
    var detail = h("div");
    var rows = [];
    var load = function (more) {
      var before = more && rows.length ? rows[rows.length - 1].id : 0;
      var q = "reports?limit=50" + (before ? "&before=" + before : "") + (filter.value.trim() ? "&mod=" + encodeURIComponent(filter.value.trim()) : "");
      api(q).then(function (r) {
        rows = more ? rows.concat(r) : r;
        clear(list).appendChild(table(["ID", "日時", "環境", "項目", "アプリ", "端末"], rows.map(function (x) {
          return [x.id, time(x.created), x.mc + " " + x.loader, itemSummary(x.body), x.app, h("span", { class: "mono" }, short(x.install))];
        }), function (i) { showReport(rows[i], detail, function () { load(false); }); }));
        if (r.length === 50) list.appendChild(h("div", { class: "row" }, h("button", { class: "b", onclick: function () { load(true); } }, "さらに読み込む")));
      }).catch(fail);
    };
    filter.onkeydown = function (e) { if (e.key === "Enter") load(false); };
    main.appendChild(h("div", { class: "row" }, filter, h("button", { class: "b", onclick: function () { load(false); } }, "検索")));
    main.appendChild(list);
    main.appendChild(detail);
    load(false);
  }

  function showReport(r, detail, reload) {
    var body = parse(r.body) || {};
    clear(detail).appendChild(h("div", { class: "panel" },
      h("h2", null, "報告 #" + r.id),
      kv([["受信", time(r.created)], ["環境", envText(body.env)], ["CrashHunter", r.app],
        ["端末", h("span", { class: "mono", title: r.install }, short(r.install))], ["IP(ハッシュ)", h("span", { class: "mono", title: r.ip }, short(r.ip))]]),
      h("div", { class: "row" },
        h("button", { class: "b d", onclick: function () { confirmDo("報告 #" + r.id + " を削除しますか?", function () { api("delete-report", { body: { id: r.id } }).then(function () { toast("削除しました"); clear(detail); reload(); }).catch(fail); }); } }, "削除"),
        h("button", { class: "b d", onclick: function () { confirmDo("この端末を遮断し、送られたデータをすべて削除しますか?", function () { api("block", { body: { installHash: r.install, reason: "admin" } }).then(function (x) { toast("遮断しました(削除 " + x.removed + "件)"); clear(detail); reload(); }).catch(fail); }); } }, "端末を遮断"),
        h("button", { class: "b d", onclick: function () { blockIpDialog({ reportId: r.id }, function () { clear(detail); reload(); }); } }, "IPを遮断")),
      (body.items || []).map(function (it) { return itemView(it); }),
      h("details", null, h("summary", { class: "muted" }, "受信したデータ(JSON)"), h("pre", null, pretty(body)))));
    detail.scrollIntoView({ behavior: "smooth" });
  }

  function refText(m) { return m ? m.id + (m.v ? " " + m.v : "") : "-"; }

  function itemView(it) {
    var box = function (title, body) { return h("div", { class: "panel", style: "background:#25272b" }, h("div", { style: "font-weight:700;margin-bottom:4px" }, title), body); };
    if (it.type === "conflict") return box("競合", kv([["Mod", it.mods.map(refText).join(" + ")], ["例外", it.ex || null], ["原因特定の試行", it.trials || null]]));
    if (it.type === "fix") return box("修正: " + fixKind(it.kind), kv([["変更前", it.from.map(refText).join(", ") || "-"], ["変更後", it.to.map(refText).join(", ") || "-"], ["結果", label(RESULT, it.result)]]));
    if (it.type === "broken") return box("壊れたファイル", kv([["Mod", refText(it.mod)], ["理由", label(REASON, it.reason)]]));
    if (it.type === "dependency") return box("前提Mod", kv([["Mod", refText(it.mod)], ["必要だったMod", refText(it.needs)], ["前提の宣言", it.declared ? "あり" : "なし(宣言漏れ)"], ["宣言の範囲", it.range || null]]));
    if (it.type === "compatible") return box("起動できた構成", h("details", null, h("summary", { class: "muted" }, it.mods.length + "個のMod"), h("div", { class: "mono", style: "margin-top:6px" }, it.mods.map(refText).join(", "))));
    if (it.type === "crash") return box("クラッシュ: " + label(CAUSE, it.cause), crashView(it));
    return box(it.type, h("pre", null, pretty(it)));
  }

  function frameText(f) { return f.c + "." + f.m + (f.src ? "(" + f.src + ")" : "") + (f.mod ? "   ← " + f.mod : ""); }

  function crashView(c) {
    return h("div", null,
      kv([["状況", label(CONTEXT, c.context) + "・" + label(SOURCE, c.source) + (c.seen ? "・30日で" + c.seen + "回" : "")],
        ["例外", c.ex ? exName(c.ex) : null], ["メッセージ", c.msg ? h("span", { class: "mono" }, c.msg) : null], ["段階", c.phase || null],
        ["原因の連鎖", (c.causes || []).length ? h("div", null, c.causes.map(function (x) { return h("div", { class: "mono" }, x.ex.split(".").pop() + (x.msg ? ": " + x.msg : "")); })) : null],
        ["関わったMod", (c.mods || []).length ? c.mods.map(refText).join(", ") : null],
        ["最大メモリ", c.heapMb ? c.heapMb + " MB" : null]]),
      (c.findings || []).length ? section("指摘", h("div", null, c.findings.map(function (f) {
        return h("div", { style: "margin-bottom:6px" },
          h("div", null, label(CAUSE, f.cause) + (f.mod ? ": " + f.mod : "") + (f.other ? " — " + f.other : "") + (f.config ? "(" + f.config + ")" : "") +
            (f.required ? " 必要: " + f.required : "") + (f.actual ? " 実際: " + f.actual : "")),
          f.line ? h("div", { class: "mono muted" }, f.line) : null);
      }))) : null,
      (c.frames || []).length ? h("details", null, h("summary", { class: "muted" }, "スタック(" + c.frames.length + "行)"), h("pre", null, c.frames.map(frameText).join("\n"))) : null,
      c.sys && Object.keys(c.sys).length ? h("details", null, h("summary", { class: "muted" }, "システム情報"), kv(Object.keys(c.sys).map(function (k) { return [label(SYS, k), c.sys[k]]; }))) : null);
  }

  function blockIpDialog(target, done) {
    var days = window.prompt("遮断する日数(0 で無期限)", "30");
    if (days === null) return;
    var n = Number(days);
    if (!(n >= 0)) { toast("日数が正しくありません", true); return; }
    var purge = window.confirm("このIPから送られたデータも削除しますか?");
    var body = { days: n, purge: purge, reason: "admin" };
    Object.keys(target).forEach(function (k) { body[k] = target[k]; });
    api("block-ip", { body: body }).then(function (x) { toast("遮断しました(削除 " + x.removed + "件)"); if (done) done(); }).catch(fail);
  }

  function runs(main) {
    main.appendChild(h("h1", null, "実行記録"));
    var kind = h("select", null, h("option", { value: "" }, "すべて"), h("option", { value: "bisect" }, "原因特定"), h("option", { value: "tune" }, "構成調整"));
    var list = h("div");
    var detail = h("div");
    var rows = [];
    var load = function (more) {
      var before = more && rows.length ? rows[rows.length - 1].id : 0;
      api("runs?limit=50" + (kind.value ? "&kind=" + kind.value : "") + (before ? "&before=" + before : "")).then(function (r) {
        rows = more ? rows.concat(r) : r;
        clear(list).appendChild(table(["ID", "日時", "種類", "結果", "所要時間", "試行", "Mod数"], rows.map(function (x) {
          var b = parse(x.body) || {};
          var ok = x.status === "FOUND" || x.status === "LAUNCHED";
          return [x.id, time(x.created), label(KIND, x.kind), h("span", null, dot(ok ? "var(--ok)" : "var(--warn)"), label(STATUS, x.status)), duration(b.durationMs), (b.trials || []).length, (b.mods || []).length];
        }), function (i) {
          var x = rows[i];
          var b = parse(x.body) || {};
          clear(detail).appendChild(h("div", { class: "panel" },
            h("h2", null, label(KIND, x.kind) + " #" + x.id + ": " + label(STATUS, x.status)),
            kv([["受信", time(x.created)], ["環境", envText(b.env)], ["CrashHunter", x.app], ["所要時間", duration(b.durationMs)], ["並列数", b.parallel],
              ["試行", (b.trials || []).length + (b.estimate ? "(見積もり " + b.estimate + ")" : "")], ["Mod数", (b.mods || []).length],
              ["原因", (b.culprits || []).length ? b.culprits.map(refText).join(", ") : null], ["必要だったMod", (b.requirements || []).length ? b.requirements.map(refText).join(", ") : null],
              ["先に疑ったMod", (b.suspects || []).length ? b.suspects.map(refText).join(", ") : null],
              ["端末", h("span", { class: "mono", title: x.install }, short(x.install))], ["IP(ハッシュ)", h("span", { class: "mono", title: x.ip }, short(x.ip))]]),
            (b.trials || []).length ? section("試行", table(["#", "並列枠", "段階", "有効なMod", "結果", "時間", "起動まで", "起動成功まで", "再起動"], b.trials.map(function (t) {
              return [t.n >= 100000 ? "-" : t.n, t.slot, label(PHASE, t.phase), t.enabled + " / " + t.pool, label(TRIAL, t.result), duration(t.ms), duration(t.toRunningMs), duration(t.toMenuMs), t.relaunches || 0];
            }))) : null,
            (b.steps || []).length ? section("構成調整の手順", table(["回", "内容", "Mod", "変更", "結果"], b.steps.map(function (st) {
              var kind = st.kind.indexOf("fix:") === 0 ? fixKind(st.kind.slice(4)) : label(STEP, st.kind);
              return [st.round, kind, st.mod ? refText(st.mod) : "-", (st.from || "") + (st.to ? " → " + st.to : "") + (st.also ? "(ほか" + st.also + "個)" : ""), st.ok == null ? "-" : st.ok ? "成功" : "失敗"];
            }))) : null,
            b.settings ? h("details", null, h("summary", { class: "muted" }, "設定"), kv(Object.keys(b.settings).map(function (k) { return [label(SETTING, k), yesNo(b.settings[k])]; }))) : null,
            h("div", { class: "row" },
              h("button", { class: "b d", onclick: function () { confirmDo("実行記録 #" + x.id + " を削除しますか?", function () { api("delete-run", { body: { id: x.id } }).then(function () { toast("削除しました"); clear(detail); load(false); }).catch(fail); }); } }, "削除"),
              h("button", { class: "b d", onclick: function () { blockIpDialog({ runId: x.id }, function () { clear(detail); load(false); }); } }, "IPを遮断")),
            h("details", null, h("summary", { class: "muted" }, "受信したデータ(JSON)"), h("pre", null, pretty(b)))));
        }));
        if (r.length === 50) list.appendChild(h("div", { class: "row" }, h("button", { class: "b", onclick: function () { load(true); } }, "さらに読み込む")));
      }).catch(fail);
    };
    kind.onchange = function () { load(false); };
    main.appendChild(h("div", { class: "row" }, kind));
    main.appendChild(list);
    main.appendChild(detail);
    load(false);
  }

  var VERDICT = { confirmed: "確定", rejected: "却下", inconclusive: "判定できず" };
  var METHOD = { static: "ファイルの中身", launch: "起動", manual: "手動" };

  function verifyPage(main) {
    main.appendChild(h("h1", null, "検証"));
    var pend = h("div");
    var done = h("div");
    main.appendChild(h("h2", null, "確認待ちの壊れたファイル"));
    main.appendChild(pend);
    main.appendChild(h("h2", null, "判定"));
    main.appendChild(done);
    var decide = function (p, verdict) {
      confirmDo(refText(p.mod) + " を「" + VERDICT[verdict] + "」にしますか?", function () {
        api("verdict", { body: { mod: { id: p.mod.id, v: p.mod.v, sha1: p.mod.sha1 }, reason: p.reason, verdict: verdict, method: "manual" } })
          .then(function () { toast(VERDICT[verdict] + "にしました"); load(); }).catch(fail);
      });
    };
    var load = function () {
      api("pending").then(function (list) {
        clear(pend).appendChild(table(["Mod", "理由", "台数", "環境", "前回", ""], list.map(function (p) {
          return [refText(p.mod), label(REASON, p.reason), p.n, p.envs.join(", "), p.tried ? "判定できず(" + time(p.tried) + ")" : "-",
            h("span", null, h("button", { class: "b", onclick: function () { decide(p, "confirmed"); } }, "確定"), " ",
              h("button", { class: "b d", onclick: function () { decide(p, "rejected"); } }, "却下"))];
        })));
      }).catch(fail);
      api("verdicts").then(function (list) {
        clear(done).appendChild(table(["Mod", "理由", "判定", "方法", "検証者", "日時", "詳細"], list.map(function (v) {
          return [v.id + " " + v.v, label(REASON, v.reason), label(VERDICT, v.verdict), label(METHOD, v.method), v.by, time(v.created), v.detail || "-"];
        })));
      }).catch(fail);
    };
    load();
  }

  function modPage(main) {
    main.appendChild(h("h1", null, "Mod"));
    var id = h("input", { placeholder: "Mod ID(例: evolvedmekanismextras)", size: 32 });
    var out = h("div");
    var show = function () {
      var v = id.value.trim().toLowerCase();
      if (!v) return;
      clear(out).appendChild(h("div", { class: "muted" }, "読み込み中…"));
      api("mod?id=" + encodeURIComponent(v)).then(function (m) {
        clear(out);
        if (m.error) { out.appendChild(h("div", { class: "err" }, m.error)); return; }
        out.appendChild(h("div", { class: "cards" },
          [["端末", m.installs], ["クラッシュの種類", m.crashes.length], ["競合", m.conflicts.length], ["起動できた構成", m.compatibleReports]]
            .map(function (c) { return h("div", { class: "card" }, h("div", { class: "n" }, c[1]), h("div", { class: "l" }, c[0])); })));
        out.appendChild(h("h2", null, "クラッシュ"));
        var crashDetail = h("div");
        out.appendChild(table(["原因", "例外", "台数", "バージョン", "環境", "状況", "最終"], m.crashes.map(function (c) {
          return [label(CAUSE, c.cause), exName(c.ex), c.installs, c.versions.join(", "), c.envs.join(", "), Object.keys(c.contexts).map(function (k) { return label(CONTEXT, k) + " " + c.contexts[k]; }).join("・"), time(c.last)];
        }), function (i) {
          var c = m.crashes[i];
          clear(crashDetail).appendChild(h("div", { class: "panel" },
            kv([["例外", exName(c.ex)], ["メッセージ", c.msg ? h("span", { class: "mono" }, c.msg) : null], ["段階", c.phase || null],
              ["原因の連鎖", (c.causes || []).length ? h("div", null, c.causes.map(function (x) { return h("div", { class: "mono" }, x.ex.split(".").pop() + (x.msg ? ": " + x.msg : "")); })) : null],
              ["一緒に入っていたMod", Object.keys(c.withMods).sort(function (a, b) { return c.withMods[b] - c.withMods[a]; }).slice(0, 15).map(function (k) { return k + "(" + c.withMods[k] + ")"; }).join(", ") || null],
              ["初回 / 最終", time(c.first) + " / " + time(c.last)]]),
            (c.frames || []).length ? h("details", { open: true }, h("summary", { class: "muted" }, "スタック"), h("pre", null, c.frames.map(frameText).join("\n"))) : null,
            (c.sys || []).length ? h("details", null, h("summary", { class: "muted" }, "システム情報(" + c.sys.length + "件)"), c.sys.map(function (sy) { return kv(Object.keys(sy).map(function (k) { return [label(SYS, k), sy[k]]; })); })) : null));
        }));
        out.appendChild(crashDetail);
        out.appendChild(h("h2", null, "競合"));
        out.appendChild(table(["Mod", "例外", "台数", "環境"], m.conflicts.map(function (c) { return [c.mods.join(" + "), c.ex || "-", c.installs, c.envs.join(", ")]; })));
        out.appendChild(h("h2", null, "修正"));
        out.appendChild(table(["種類", "変更前", "変更後", "直った", "別のエラー", "直らない"], m.fixes.map(function (f) { return [fixKind(f.kind), f.from || "-", f.to || "-", f.fixed, f.changed, f.failed]; })));
        out.appendChild(h("h2", null, "前提Mod"));
        out.appendChild(table(["Mod", "必要だったMod", "前提の宣言", "宣言の範囲", "台数"], m.dependencies.map(function (d) { return [d.mod, d.needs, d.declared ? "あり" : "なし(宣言漏れ)", d.range || "-", d.installs]; })));
        out.appendChild(h("h2", null, "壊れたファイル"));
        out.appendChild(table(["バージョン", "理由", "台数"], m.broken.map(function (b) { return [b.version || "-", label(REASON, b.reason), b.installs]; })));
      }).catch(fail);
    };
    id.onkeydown = function (e) { if (e.key === "Enter") show(); };
    main.appendChild(h("div", { class: "row" }, id, h("button", { class: "b", onclick: show }, "表示")));
    main.appendChild(out);
  }

  function blocks(main) {
    main.appendChild(h("h1", null, "遮断"));
    var ipInput = h("input", { placeholder: "IPアドレス", size: 28 });
    main.appendChild(h("div", { class: "row" }, ipInput, h("button", { class: "b d", onclick: function () { if (ipInput.value.trim()) blockIpDialog({ ip: ipInput.value.trim() }, function () { render(); }); } }, "IPを遮断")));
    var ips = h("div");
    var installs = h("div");
    main.appendChild(h("h2", null, "IPアドレス"));
    main.appendChild(ips);
    main.appendChild(h("h2", null, "端末"));
    main.appendChild(installs);
    api("blocked-ips").then(function (r) {
      ips.appendChild(table(["IP(ハッシュ)", "理由", "開始", "期限", ""], r.map(function (x) {
        return [h("span", { class: "mono" }, short(x.ip)), x.reason || "-", time(x.created), x.expires ? time(x.expires) : "無期限",
          h("button", { class: "b", onclick: function () { confirmDo("遮断を解除しますか?", function () { api("unblock-ip", { body: { ipHash: x.ip } }).then(function () { toast("解除しました"); render(); }).catch(fail); }); } }, "解除")];
      })));
    }).catch(fail);
    api("blocked").then(function (r) {
      installs.appendChild(table(["端末(ハッシュ)", "理由", "開始", ""], r.map(function (x) {
        return [h("span", { class: "mono" }, short(x.install)), x.reason || "-", time(x.created),
          h("button", { class: "b", onclick: function () { confirmDo("遮断を解除しますか?(削除したデータは戻りません)", function () { api("unblock", { body: { installHash: x.install } }).then(function () { toast("解除しました"); render(); }).catch(fail); }); } }, "解除")];
      })));
    }).catch(fail);
  }

  render();
})();
