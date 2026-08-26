import { createExecutionContext, env } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import { MAX_JSON_BYTES } from "../src/http";
import { csvCell } from "../src/reports";
import worker from "../src/index";
import { sha256 } from "../src/auth";
import { resetPosDb } from "./db";

const userId = "11000000-0000-4000-8000-000000000001";
const otherUserId = "11000000-0000-4000-8000-000000000099";
const deviceId = "20000000-0000-4000-8000-000000000001";
const token = "test-access-token";
const dashboardToken = "dashboard-test-session-token";
const ownEventId = "70000000-0000-4000-8000-000000000001";
const otherEventId = "70000000-0000-4000-8000-000000000099";

beforeEach(async () => {
  await resetPosDb(env.POS_DB);
  await env.POS_DB.batch([
    env.POS_DB.prepare(
      "INSERT INTO users (id, google_sub, cloud_epoch, created_at_utc) VALUES (?, 'google-test', 1, '2026-08-24T00:00:00Z')",
    ).bind(userId),
    env.POS_DB.prepare(
      "INSERT INTO users (id, google_sub, cloud_epoch, created_at_utc) VALUES (?, 'google-other', 1, '2026-08-24T00:00:00Z')",
    ).bind(otherUserId),
    env.POS_DB.prepare(
      `INSERT INTO devices (id, user_id, short_code, name, status, cloud_epoch, registered_at_utc, last_seen_at_utc)
       VALUES (?, ?, 'A', 'Test', 'ACTIVE', 1, '2026-08-24T00:00:00Z', '2026-08-24T00:00:00Z')`,
    ).bind(deviceId, userId),
    env.POS_DB.prepare(
      `INSERT INTO sessions (token_hash, user_id, device_id, created_at_utc, expires_at_utc)
       VALUES (?, ?, ?, '2026-08-24T00:00:00Z', '2099-01-01T00:00:00Z')`,
    ).bind(await sha256(token), userId, deviceId),
    env.POS_DB.prepare(
      `INSERT INTO dashboard_sessions (token_hash, user_id, created_at_utc, expires_at_utc)
       VALUES (?, ?, '2026-08-24T00:00:00Z', '2099-01-01T00:00:00Z')`,
    ).bind(await sha256(dashboardToken), userId),
    event(userId, ownEventId, "自己的市集"),
    event(otherUserId, otherEventId, "別人的市集"),
  ]);
});

describe("local security attack surface", () => {
  it("puts clickjacking and sniffing headers on JSON and dashboard pages", async () => {
    const health = await fetchPath("/health");
    expect(health.status).toBe(200);
    expectSecurityHeaders(health);
    expect(health.headers.get("access-control-allow-origin")).toBeNull();

    const dashboard = await fetchPath("/dashboard");
    expect(dashboard.status).toBe(200);
    expect(dashboard.headers.get("content-security-policy")).toContain("frame-ancestors 'none'");
    expect(dashboard.headers.get("x-frame-options")).toBe("DENY");
    expect(dashboard.headers.get("permissions-policy")).toContain("camera=()");
  });

  it("rejects unauthenticated reads and writes on tenant APIs", async () => {
    expect((await fetchPath("/v2/sync/batch", { method: "POST", json: {} })).status).toBe(401);
    expect((await fetchPath("/v2/bootstrap?group=PRODUCTS")).status).toBe(401);
    expect((await fetchPath("/v2/account", { method: "DELETE" })).status).toBe(401);
    expect((await fetchPath("/v2/account/cloud", { method: "DELETE" })).status).toBe(401);
    expect((await fetchPath("/v2/reports/events")).status).toBe(401);
    expect((await fetchPath("/v2/sync/batch", {
      method: "POST",
      json: {},
      headers: { authorization: "Bearer not-a-real-session" },
    })).status).toBe(401);
  });

  it("does not enable CORS or honor injected request IDs that are not UUIDs", async () => {
    const response = await fetchPath("/v2/sync/batch", {
      method: "POST",
      json: {},
      headers: {
        origin: "https://attacker.example",
        "x-request-id": "<script>alert(1)</script>",
      },
    });
    expect(response.status).toBe(401);
    expect(response.headers.get("access-control-allow-origin")).toBeNull();
    expect(response.headers.get("access-control-allow-credentials")).toBeNull();
    const body = await response.json() as { requestId: string };
    expect(body.requestId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    expect(body.requestId).not.toContain("<script>");
  });

  it("rejects oversized, non-JSON, and non-object bodies before sync", async () => {
    const tooLarge = await fetchPath("/v2/auth/refresh", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: `{"padding":"${"x".repeat(MAX_JSON_BYTES)}"}`,
    });
    expect(tooLarge.status).toBe(413);

    const media = await fetchPath("/v2/auth/refresh", {
      method: "POST",
      headers: { "content-type": "text/plain" },
      body: '{"refreshToken":"x","deviceId":"20000000-0000-4000-8000-000000000001"}',
    });
    expect(media.status).toBe(415);

    const arrayBody = await fetchPath("/v2/auth/refresh", {
      method: "POST",
      json: ["not", "an", "object"],
    });
    expect(arrayBody.status).toBe(400);

    const sqlPath = await fetchPath(`/v2/reports/events/${encodeURIComponent("1 OR 1=1")}`, {
      headers: { cookie: `stallpos_dashboard=${dashboardToken}` },
    });
    expect(sqlPath.status).toBe(400);
  });

  it("isolates dashboard reports to the signed-in user", async () => {
    const list = await fetchPath("/v2/reports/events", {
      headers: { cookie: `stallpos_dashboard=${dashboardToken}` },
    });
    expect(list.status).toBe(200);
    const events = (await list.json() as { events: Array<{ id: string }> }).events.map((row) => row.id);
    expect(events).toContain(ownEventId);
    expect(events).not.toContain(otherEventId);

    const stolen = await fetchPath(`/v2/reports/events/${otherEventId}`, {
      headers: { cookie: `stallpos_dashboard=${dashboardToken}` },
    });
    expect(stolen.status).toBe(404);

    const forgedCookie = await fetchPath("/v2/reports/events", {
      headers: { cookie: "stallpos_dashboard=aaaaaaaaaaaaaaaaaaaa" },
    });
    expect(forgedCookie.status).toBe(401);
  });

  it("does not leak reports through CSV, path tricks, or extra HTTP methods", async () => {
    expect((await fetchPath(`/v2/reports/events/${ownEventId}/transactions.csv`)).status).toBe(401);
    expect((await fetchPath(`/v2/reports/events/${otherEventId}/transactions.csv`, {
      headers: { cookie: `stallpos_dashboard=${dashboardToken}` },
    })).status).toBe(404);

    const ownCsv = await fetchPath(`/v2/reports/events/${ownEventId}/transactions.csv`, {
      headers: { cookie: `stallpos_dashboard=${dashboardToken}` },
    });
    expect(ownCsv.status).toBe(200);
    expect(ownCsv.headers.get("x-frame-options")).toBe("DENY");
    expect(ownCsv.headers.get("content-disposition")).toMatch(/^attachment; filename="stallpos-SEC-transactions.csv"$/);

    const nested = await fetchPath("/dashboard/../v2/reports/events");
    expect(nested.status).toBe(401);

    expect((await fetchPath("/health", { method: "PUT" })).status).toBe(404);
    expect((await fetchPath("/v2/sync/batch", { method: "OPTIONS" })).headers.get("access-control-allow-origin")).toBeNull();
    expect((await fetchPath("/v2/bootstrap?group=sales%20UNION%20SELECT%201")).status).toBe(401);
    expect((await fetchPath("/v2/bootstrap?group=sales%20UNION%20SELECT%201", {
      headers: { authorization: `Bearer ${token}` },
    })).status).toBe(400);
  });

  it("neutralizes spreadsheet formulas in CSV cells", () => {
    expect(csvCell("=1+1")).toBe("'=1+1");
    expect(csvCell("+cmd")).toBe("'+cmd");
    expect(csvCell("@SUM(A1)")).toBe("'@SUM(A1)");
    expect(csvCell("-cmd")).toBe("'-cmd");
    expect(csvCell(-10)).toBe("-10");
    expect(csvCell("徽章")).toBe("徽章");
    expect(csvCell('say "hi"')).toBe('"say ""hi"""');
  });

  it("rejects garbage transfer claims and does not create sessions", async () => {
    const claimed = await fetchPath("/v2/devices/transfer/claim", {
      method: "POST",
      json: { transferToken: "a".repeat(32), deviceId: "20000000-0000-4000-8000-000000000099", deviceName: "Stolen" },
    });
    expect(claimed.status).toBe(409);
    expect(await env.POS_DB.prepare("SELECT COUNT(*) AS count FROM sessions").first("count")).toBe(1);
  });
});

function event(owner: string, id: string, name: string) {
  return env.POS_DB.prepare(
    `INSERT INTO events (user_id,id,name,code,event_type,start_at_utc,end_at_utc,timezone,location,status,updated_at_utc,payload_json)
     VALUES (?,?,?,'SEC','MARKET','2026-08-24T00:00:00Z','2026-08-25T00:00:00Z','Asia/Taipei','台北','CLOSED','2026-08-25T00:00:00Z','{}')`,
  ).bind(owner, id, name);
}

function expectSecurityHeaders(response: Response) {
  expect(response.headers.get("x-content-type-options")).toBe("nosniff");
  expect(response.headers.get("x-frame-options")).toBe("DENY");
  expect(response.headers.get("referrer-policy")).toBe("no-referrer");
  expect(response.headers.get("content-security-policy")).toContain("frame-ancestors 'none'");
  expect(response.headers.get("cache-control")).toBe("no-store");
}

async function fetchPath(
  path: string,
  init: {
    method?: string;
    json?: unknown;
    body?: string;
    headers?: Record<string, string>;
  } = {},
): Promise<Response> {
  const headers = new Headers(init.headers);
  let body = init.body;
  if (init.json !== undefined) {
    headers.set("content-type", headers.get("content-type") ?? "application/json");
    body = JSON.stringify(init.json);
  }
  return worker.fetch(new Request(`https://stallpos.test${path}`, {
    method: init.method ?? "GET",
    headers,
    body,
  }), env, createExecutionContext());
}
