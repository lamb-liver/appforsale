import { env } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import { rateLimitResponse, runScheduledOps, sentryOptions } from "../src/ops";
import { resetPosDb } from "./db";

const now = new Date("2026-08-24T12:00:00Z");

beforeEach(async () => resetPosDb(env.POS_DB));

describe("operations gates", () => {
  it("deletes audit metadata older than 180 days only", async () => {
    await seedUser("ops-user", "ops-sub", "2024-01-01T00:00:00Z");
    await env.POS_DB.batch([
      audit("old-audit", "2026-02-24T11:59:59Z"),
      audit("new-audit", "2026-02-25T12:00:00Z"),
    ]);
    expect((await runScheduledOps(env, now)).deletedAudits).toBe(1);
    expect(await env.POS_DB.prepare("SELECT id FROM audit_logs").first("id")).toBe("new-audit");
  });

  it("keeps inactive cloud accounts until the user explicitly deletes them", async () => {
    await seedUser("inactive-user", "inactive-sub", "2023-01-01T00:00:00Z");
    await runScheduledOps(env, now);
    expect(await env.POS_DB.prepare("SELECT id FROM users WHERE id='inactive-user'").first("id")).toBe("inactive-user");
  });

  it("fails closed when the native rate limiter rejects a request", async () => {
    const limitedEnv = {
      AUTH_RATE_LIMITER: { limit: async () => ({ success: false }) },
      API_RATE_LIMITER: { limit: async () => ({ success: true }) },
    } as unknown as Env;
    const response = await rateLimitResponse(
      new Request("https://stallpos.test/v2/auth/google", { headers: { "cf-connecting-ip": "192.0.2.1" } }),
      limitedEnv,
      "request-1",
    );
    expect(response?.status).toBe(429);
    expect(response?.headers.get("retry-after")).toBe("60");
  });

  it("strips request and user data before reporting an error", () => {
    const options = sentryOptions({ SENTRY_DSN: "https://public@example.invalid/1" } as unknown as Env);
    const cleaned = options.beforeSend?.({
      type: undefined,
      user: { email: "owner@example.com" },
      request: { data: "token" },
      breadcrumbs: [{ message: "product payload" }],
      extra: { transaction: "full sale" },
    }, {});
    expect(cleaned).toMatchObject({ user: undefined, request: undefined, breadcrumbs: undefined, extra: undefined });
  });
});

async function seedUser(id: string, sub: string, created: string) {
  await env.POS_DB.prepare(
    "INSERT INTO users (id,google_sub,cloud_epoch,created_at_utc) VALUES (?,?,1,?)",
  ).bind(id, sub, created).run();
}

function audit(id: string, occurred: string): D1PreparedStatement {
  return env.POS_DB.prepare(
    `INSERT INTO audit_logs (id,user_id,request_id,action,occurred_at_utc)
     VALUES (?,'ops-user','request','TEST',?)`,
  ).bind(id, occurred);
}
