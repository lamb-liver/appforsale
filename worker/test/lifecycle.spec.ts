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
let googleSub: string;

beforeEach(async () => {
  await resetPosDb(env.POS_DB);
  googleSub = `google-lifecycle-${crypto.randomUUID()}`;
  await env.POS_DB.batch([
    env.POS_DB.prepare(
      "INSERT INTO users (id,google_sub,cloud_epoch,created_at_utc,email) VALUES (?,?,1,'2026-08-24T00:00:00Z','owner@example.com')",
    ).bind(userId, googleSub),
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
    const retriedClaim = await api("/v2/devices/transfer/claim", {
      transferToken,
      deviceId: targetDevice,
      deviceName: "Target",
    });
    expect(retriedClaim.status).toBe(200);
    expect((await retriedClaim.json() as { commitToken: string }).commitToken).toBe(claim.commitToken);
    expect(claim.commitToken).not.toBe(transferToken);

    const committed = await api("/v2/devices/transfer/commit", { commitToken: claim.commitToken });
    expect(committed.status).toBe(200);
    expect(await activeDevice()).toBe(targetDevice);
    expect((await api("/v2/devices/transfer/commit", { commitToken: claim.commitToken })).status).toBe(409);
    const oldRequest = await api("/v2/bootstrap?group=PRODUCTS", undefined, accessToken, "GET");
    expect(await oldRequest.json()).toMatchObject({ code: "DEVICE_RETIRED" });
  });

  it("retires only the transfer source when another device is also ACTIVE", async () => {
    const helper = "20000000-0000-4000-8000-000000000092";
    await env.POS_DB.prepare(
      `INSERT INTO devices (id,user_id,short_code,name,status,cloud_epoch,registered_at_utc,last_seen_at_utc)
       VALUES (?,?,'helper','Helper','ACTIVE',1,'2026-08-24T00:00:00Z','2026-08-24T00:00:00Z')`,
    ).bind(helper, userId).run();
    const created = await api("/v2/devices/transfer", {}, accessToken);
    const transferToken = (await created.json() as { transferToken: string }).transferToken;
    const claimed = await api("/v2/devices/transfer/claim", {
      transferToken,
      deviceId: targetDevice,
      deviceName: "Target",
    });
    const commitToken = (await claimed.json() as { commitToken: string }).commitToken;
    expect((await api("/v2/devices/transfer/commit", { commitToken })).status).toBe(200);
    const rows = await env.POS_DB.prepare(
      "SELECT id, status FROM devices WHERE user_id=? ORDER BY id",
    ).bind(userId).all<{ id: string; status: string }>();
    const byId = Object.fromEntries(rows.results.map((row) => [row.id, row.status]));
    expect(byId[sourceDevice]).toBe("RETIRED");
    expect(byId[helper]).toBe("ACTIVE");
    expect(byId[targetDevice]).toBe("ACTIVE");
  });

  it("rejects commit when the source device is already RETIRED", async () => {
    const created = await api("/v2/devices/transfer", {}, accessToken);
    const transferToken = (await created.json() as { transferToken: string }).transferToken;
    const claimed = await api("/v2/devices/transfer/claim", {
      transferToken,
      deviceId: targetDevice,
      deviceName: "Target",
    });
    const commitToken = (await claimed.json() as { commitToken: string }).commitToken;
    await env.POS_DB.prepare(
      "UPDATE devices SET status='RETIRED', retired_at_utc='2026-08-24T01:00:00Z' WHERE id=?",
    ).bind(sourceDevice).run();
    const committed = await api("/v2/devices/transfer/commit", { commitToken });
    expect(committed.status).toBe(409);
    expect(await committed.json()).toMatchObject({ code: "TRANSFER_INVALID" });
    expect(await env.POS_DB.prepare(
      "SELECT COUNT(*) AS count FROM devices WHERE user_id=? AND status='ACTIVE'",
    ).bind(userId).first("count")).toBe(0);
  });

  it("keeps the deletion barrier when POS cleanup fails, then reconciles it", async () => {
    await env.POS_DB.prepare(
      `CREATE TRIGGER fail_cloud_delete BEFORE UPDATE OF deleted_at_utc ON users
       BEGIN SELECT RAISE(ABORT,'simulated failure'); END`,
    ).run();
    const response = await api("/v2/account/cloud", undefined, accessToken, "DELETE");
    expect(response.status).toBe(202);
    expect(await env.DELETION_DB.prepare(
      "SELECT COUNT(*) AS count FROM deletion_tombstones WHERE google_sub=?",
    ).bind(googleSub).first("count")).toBe(1);
    expect((await api("/v2/bootstrap?group=PRODUCTS", undefined, accessToken, "GET")).status).toBe(409);

    await env.POS_DB.prepare("DROP TRIGGER fail_cloud_delete").run();
    await reconcileDeletionTombstones(env);
    expect(await env.POS_DB.prepare("SELECT deleted_at_utc FROM users WHERE id=?").bind(userId).first("deleted_at_utc")).toBeTruthy();
    expect(await env.POS_DB.prepare("SELECT email FROM users WHERE id=?").bind(userId).first("email")).toBeNull();
    expect(await env.POS_DB.prepare("SELECT COUNT(*) AS count FROM sessions WHERE user_id=?").bind(userId).first("count")).toBe(0);
  });

  it("does not truncate transfer bootstrap data at 500 rows", async () => {
    const rows = Array.from({ length: 501 }, (_, index) => {
      const id = `category-${index}`;
      const payload = JSON.stringify({
        id,
        categoryType: "PRODUCT",
        name: `Category ${index}`,
        sortOrder: index,
        updatedAtUtc: "2026-08-24T00:00:00Z",
        deletedAtUtc: null,
      });
      return env.POS_DB.prepare(
        `INSERT INTO categories (user_id,id,category_type,name,sort_order,updated_at_utc,deleted_at_utc,payload_json)
         VALUES (?,?,?,?,?,?,NULL,?)`,
      ).bind(userId, id, "PRODUCT", `Category ${index}`, index, "2026-08-24T00:00:00Z", payload);
    });
    await env.POS_DB.batch(rows);
    const created = await api("/v2/devices/transfer", {}, accessToken);
    expect(created.status, await created.clone().text()).toBe(200);
    const transferToken = (await created.json() as { transferToken: string }).transferToken;
    const claimed = await api("/v2/devices/transfer/claim", {
      transferToken,
      deviceId: targetDevice,
      deviceName: "Target",
    });
    expect(claimed.status, await claimed.clone().text()).toBe(200);
    const bootstrap = (await claimed.json() as { bootstrap: { products: unknown[] } }).bootstrap;
    expect(bootstrap.products).toHaveLength(501);
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
