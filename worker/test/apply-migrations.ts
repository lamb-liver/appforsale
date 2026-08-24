import { applyD1Migrations, env } from "cloudflare:test";

await applyD1Migrations(env.POS_DB, env.TEST_POS_MIGRATIONS);
await applyD1Migrations(env.DELETION_DB, env.TEST_DELETION_MIGRATIONS);
