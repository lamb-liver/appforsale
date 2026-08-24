import { createExecutionContext, env } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import worker from "../src/index";
import { reconcileDeletionTombstones } from "../src/lifecycle";
import { sha256 } from "../src/auth";
import { resetPosDb } from "./db";

const userId = "11000000-0000-4000-8000-000000000090";
const sourceDevice = "20000000-0000-4000-8000-000000000090";
const targetDevice = "20000000-0000-4000-8000-000000000091";
const accessToken = "lifecycle-access";

beforeEach(async () => {
  await resetPosDb(env.POS_DB);
  await env.POS_DB.batch([
    env.POS_DB.prepare(
      "INSERT INTO users (id,google_sub,cloud_epoch,created_at_utc,email) VALUES (?,'google-lifecycle',1,'2026-08-24T00:00:00Z','owner@example.com')",
    ).bind(userId),
    env.POS_DB.prepare(
      `INSERT INTO devices (id,user_id,short_code,name,status,cloud_epoch,registered_at_utc,last_seen_at_utc)
       VALUES (?,?,'source','Source','ACTIVE',1,'2026-08-24T00:00:00Z','2026-08-24T00:00:00Z')`,
    ).bind(sourceDevice, userId),
    env.POS_DB.prepare(
      "INSERT INTO sessions (token_hash,user_id,device_id,created_at_utc,expires_at_utc) VALUES (?,?,?,'2026-08-24T00:00:00Z','2099-01-01T00:00:00Z')",
    ).bind(await sha256(accessToken), userId, sourceDevice),
  ]);
});

describe("device and deletion lifecycle", () => {
  it("switches the active device only after bootstrap has been claimed and committed", async () => {
    const created = await api("/v2/devices/transfer", {}, accessToken);
    const transferToken = (await created.json() as { transferToken: string }).transferToken;
    const claimed = await api("/v2/devices/transfer/claim", {
      transferToken,
      deviceId: targetDevice,
      deviceName: "Target",
    });
    const claim = await claimed.json() as { commitToken: string; bootstrap: Record<string, unknown[]> };
    expect(claim.bootstrap).toMatchObject({ products: [], transactions: [] });
    expect(await activeDevice()).toBe(sourceDevice);

    const committed = await api("/v2/devices/transfer/commit", { commitToken: claim.commitToken });
    expect(committed.status).toBe(200);
    expect(await activeDevice()).toBe(targetDevice);
    expect((await api("/v2/devices/transfer/commit", { commitToken: claim.commitToken })).status).toBe(409);
    const oldRequest = await api("/v2/bootstrap?group=PRODUCTS", undefined, accessToken, "GET");
    expect(await oldRequest.json()).toMatchObject({ code: "DEVICE_RETIRED" });
  });

  it("keeps the deletion barrier when POS cleanup fails, then reconciles it", async () => {
    await env.POS_DB.prepare(
      `CREATE TRIGGER fail_cloud_delete BEFORE UPDATE OF deleted_at_utc ON users
       WHEN NEW.google_sub='google-lifecycle' BEGIN SELECT RAISE(ABORT,'simulated failure'); END`,
    ).run();
    const response = await api("/v2/account/cloud", undefined, accessToken, "DELETE");
    expect(response.status).toBe(202);
    expect(await env.DELETION_DB.prepare(
      "SELECT COUNT(*) AS count FROM deletion_tombstones WHERE google_sub='google-lifecycle'",
    ).first("count")).toBe(1);
    expect((await api("/v2/bootstrap?group=PRODUCTS", undefined, accessToken, "GET")).status).toBe(409);

    await env.POS_DB.prepare("DROP TRIGGER fail_cloud_delete").run();
    await reconcileDeletionTombstones(env);
    expect(await env.POS_DB.prepare("SELECT deleted_at_utc FROM users WHERE id=?").bind(userId).first("deleted_at_utc")).toBeTruthy();
    expect(await env.POS_DB.prepare("SELECT email FROM users WHERE id=?").bind(userId).first("email")).toBeNull();
    expect(await env.POS_DB.prepare("SELECT COUNT(*) AS count FROM sessions WHERE user_id=?").bind(userId).first("count")).toBe(0);
  });
});

async function activeDevice(): Promise<string | null> {
  return env.POS_DB.prepare("SELECT id FROM devices WHERE user_id=? AND status='ACTIVE'").bind(userId).first<string>("id");
}

async function api(path: string, body?: unknown, bearer?: string, method = "POST"): Promise<Response> {
  const headers = new Headers();
  if (body !== undefined) headers.set("content-type", "application/json");
  if (bearer) headers.set("authorization", `Bearer ${bearer}`);
  const context = createExecutionContext();
  return worker.fetch(new Request(`https://stallpos.test${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  }), env, context);
}
