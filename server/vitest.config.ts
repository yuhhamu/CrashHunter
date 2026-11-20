import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { generateKeyPairSync } from "node:crypto";
import { defineConfig } from "vitest/config";

const pair = generateKeyPairSync("ed25519");
const testKey = {
  priv: pair.privateKey.export({ type: "pkcs8", format: "der" }).toString("base64"),
  pub: pair.publicKey.export({ type: "spki", format: "der" }).toString("base64"),
};

export default defineConfig(async () => {
  const migrations = await readD1Migrations("./migrations");
  return {
    plugins: [
      cloudflareTest({
        wrangler: { configPath: "./wrangler.toml" },
        miniflare: { bindings: { TEST_MIGRATIONS: migrations, APP_TOKEN: "app-token", ADMIN_TOKEN: "admin-token", SALT: "salt", LIMIT_PER_INSTALL_DAY: "5", SNAP_SIGN_KEY: testKey.priv, SNAP_TEST_PUB: testKey.pub } },
      }),
    ],
    test: { setupFiles: ["./test/setup.ts"] },
  };
});
