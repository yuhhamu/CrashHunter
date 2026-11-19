# CrashHunter Database (server)

Cloudflare Workers + D1 + KV. Public URL: `https://db.yuhhamu.com/crashhunter/v1/...` through the gateway worker in `gateway/` (routes by the first path segment with service bindings).

| Path | Method | Auth | |
|---|---|---|---|
| `/v1/reports` | POST | `x-ch-token` | Store a report (validated, rate limited) |
| `/v1/snapshot?mc=&loader=` | GET | `x-ch-token` | Aggregated data for one Minecraft version and loader |
| `/v1/forget` | POST | `x-ch-token` | Delete every report of an install ID |
| `/v1/admin/stats` | GET | Bearer | Counts |
| `/v1/admin/reports` | GET | Bearer | Latest reports |
| `/v1/admin/block` | POST | Bearer | Block an install and delete its reports |
| `/v1/admin/rebuild` | POST | Bearer | Rebuild every snapshot now |
| `/v1/admin/export?mc=&loader=` | GET | Bearer | Snapshot for a data request |

Snapshots are rebuilt every hour (cron) for versions that received reports.

## Deploy

```sh
npm install --legacy-peer-deps
npx wrangler login
npx wrangler d1 create crashhunter-db          # put the id into wrangler.toml
npx wrangler kv namespace create crashhunter-db-snapshots   # put the id into wrangler.toml
npx wrangler secret put APP_TOKEN              # same value as CH_DB_TOKEN when building CrashHunter
npx wrangler secret put ADMIN_TOKEN
npx wrangler secret put SALT
npm run migrate
npm run deploy
```

## Test

```sh
npm test
npm run check
```
