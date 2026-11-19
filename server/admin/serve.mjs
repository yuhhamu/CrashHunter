import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const envFile = process.env.CH_ADMIN_ENV || path.resolve(here, "..", "..", ".env");
const port = Number(process.env.CH_ADMIN_PORT || 8790);
const remote = process.env.CH_ADMIN_REMOTE || "https://db.yuhhamu.com/crashhunter/v1/admin/";

function readToken() {
  const text = fs.readFileSync(envFile, "utf8");
  const m = text.match(/^CH_DB_ADMIN_TOKEN=(.+)$/m);
  if (!m) throw new Error("CH_DB_ADMIN_TOKEN not found in " + envFile);
  return m[1].trim();
}

const token = readToken();
const files = {
  "/": ["index.html", "text/html"],
  "/index.html": ["index.html", "text/html"],
  "/app.js": ["app.js", "text/javascript"],
  "/admin/app.js": ["app.js", "text/javascript"],
};
const security = {
  "content-security-policy": "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
  "x-frame-options": "DENY",
  "x-content-type-options": "nosniff",
  "referrer-policy": "no-referrer",
  "cache-control": "no-store",
};
const allowedHosts = new Set([`127.0.0.1:${port}`, `localhost:${port}`]);

function send(res, status, type, body) {
  res.writeHead(status, { "content-type": `${type}; charset=utf-8`, ...security });
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  try {
    if (!allowedHosts.has(req.headers.host || "")) return send(res, 403, "text/plain", "forbidden");
    const url = new URL(req.url, `http://${req.headers.host}`);
    if (url.pathname === "/favicon.ico") { res.writeHead(204); return res.end(); }
    const file = files[url.pathname];
    if (file && req.method === "GET") return send(res, 200, file[1], fs.readFileSync(path.join(here, file[0])));
    if (url.pathname.startsWith("/api/")) {
      if (req.method === "POST") {
        const origin = req.headers.origin;
        if (!origin || !allowedHosts.has(origin.replace(/^http:\/\//, ""))) return send(res, 403, "text/plain", "forbidden");
      } else if (req.method !== "GET") return send(res, 405, "text/plain", "method");
      const chunks = [];
      for await (const c of req) { chunks.push(c); if (chunks.reduce((n, x) => n + x.length, 0) > 65536) return send(res, 413, "text/plain", "too large"); }
      const upstream = await fetch(remote + url.pathname.slice(5) + url.search, {
        method: req.method,
        headers: { authorization: "Bearer " + token, ...(req.method === "POST" ? { "content-type": "application/json" } : {}) },
        body: req.method === "POST" ? Buffer.concat(chunks) : undefined,
      });
      return send(res, upstream.status, "application/json", await upstream.text());
    }
    send(res, 404, "text/plain", "not found");
  } catch (e) {
    send(res, 502, "application/json", JSON.stringify({ error: String(e && e.message || e) }));
  }
});

server.listen(port, "127.0.0.1", () => {
  console.log(`CrashHunter Database admin: http://127.0.0.1:${port}/`);
});
