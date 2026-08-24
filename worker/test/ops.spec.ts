import { env } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import { rateLimitResponse, runScheduledOps, sendInactivityNotifications, sentryOptions } from "../src/ops";
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

  it("sends each 60-day inactivity warning once per activity baseline", async () => {
    await seedUser("mail-user", "mail-sub", "2024-10-20T00:00:00Z", "owner@example.com");
    await seedUser("mail-user-seven", "mail-sub-seven", "2024-08-25T00:00:00Z", "seven@example.com");
    const sent: EmailMessageBuilder[] = [];
    const sender = {
      async send(message: EmailMessage | EmailMessageBuilder): Promise<EmailSendResult> {
        sent.push(message as EmailMessageBuilder);
        return { messageId: `test-${sent.length}` };
      },
    };
    const opsEnv = { POS_DB: env.POS_DB, EMAIL_FROM: "notify@example.com" } as unknown as Env;
    expect(await sendInactivityNotifications(opsEnv, now, sender)).toBe(2);
    expect(await sendInactivityNotifications(opsEnv, now, sender)).toBe(0);
    expect(sent).toHaveLength(2);
    expect(sent).toEqual(expect.arrayContaining([
      expect.objectContaining({ to: "owner@example.com", subject: expect.stringContaining("60") }),
      expect.objectContaining({ to: "seven@example.com", subject: expect.stringContaining("7") }),
    ]));
  });

  it("skips inactivity email when the sender address is not configured", async () => {
    const sender = { send: async () => ({ messageId: "unused" }) };
    expect(await sendInactivityNotifications({ POS_DB: env.POS_DB } as unknown as Env, now, sender)).toBe(0);
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

async function seedUser(id: string, sub: string, created: string, email: string | null = null) {
  await env.POS_DB.prepare(
    "INSERT INTO users (id,google_sub,cloud_epoch,created_at_utc,email) VALUES (?,?,1,?,?)",
  ).bind(id, sub, created, email).run();
}

function audit(id: string, occurred: string): D1PreparedStatement {
  return env.POS_DB.prepare(
    `INSERT INTO audit_logs (id,user_id,request_id,action,occurred_at_utc)
     VALUES (?,'ops-user','request','TEST',?)`,
  ).bind(id, occurred);
}
