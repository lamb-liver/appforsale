import path from "node:path";
import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [
    cloudflareTest(async () => ({
      miniflare: {
        d1Databases: ["POS_DB", "DELETION_DB"],
        bindings: {
          GOOGLE_CLIENT_IDS: "stallpos-test.apps.googleusercontent.com",
          DASHBOARD_GOOGLE_CLIENT_ID: "stallpos-test.apps.googleusercontent.com",
          SENTRY_DSN: "",
          TRANSFER_TOKEN_SECRET: "stallpos-test-transfer-secret-32-bytes",
          TEST_POS_MIGRATIONS: await readD1Migrations(path.join(import.meta.dirname, "migrations/pos")),
          TEST_DELETION_MIGRATIONS: await readD1Migrations(path.join(import.meta.dirname, "migrations/deletion")),
        },
      },
    })),
  ],
  test: {
    setupFiles: ["./test/apply-migrations.ts"],
  },
});
