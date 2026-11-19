import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig(async () => {
  const migrations = await readD1Migrations("./migrations");
  return {
    plugins: [
      cloudflareTest({
        wrangler: { configPath: "./wrangler.toml" },
        miniflare: { bindings: { TEST_MIGRATIONS: migrations, APP_TOKEN: "app-token", ADMIN_TOKEN: "admin-token", SALT: "salt", LIMIT_PER_INSTALL_DAY: "5" } },
      }),
    ],
    test: { setupFiles: ["./test/setup.ts"] },
  };
});
