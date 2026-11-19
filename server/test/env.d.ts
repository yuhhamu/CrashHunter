declare namespace Cloudflare {
  interface Env {
    DB: D1Database;
    SNAP: KVNamespace;
    TEST_MIGRATIONS: import("cloudflare:test").D1Migration[];
    APP_TOKEN: string;
    ADMIN_TOKEN: string;
    SALT: string;
  }
}
