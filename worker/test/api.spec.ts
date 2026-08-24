import { createExecutionContext, env } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import masterBatch from "../../contracts/v2/fixtures/master-batch.json";
import saleBatch from "../../contracts/v2/fixtures/sale-batch.json";
import duplicateBatch from "../../contracts/v2/fixtures/duplicate-batch.json";
import voidBatch from "../../contracts/v2/fixtures/void-batch.json";
import invalidBatch from "../../contracts/v2/fixtures/invalid-batch.json";
import worker from "../src/index";
import { sha256 } from "../src/auth";
import { resetPosDb } from "./db";

const userId = "11000000-0000-4000-8000-000000000001";
const deviceId = "20000000-0000-4000-8000-000000000001";
const token = "test-access-token";

beforeEach(async () => {
  await resetPosDb(env.POS_DB);
  const tokenHash = await sha256(token);
  await env.POS_DB.batch([
    env.POS_DB.prepare(
      "INSERT INTO users (id, google_sub, cloud_epoch, created_at_utc) VALUES (?, 'google-test', 1, '2026-08-24T00:00:00Z')",
    ).bind(userId),
    env.POS_DB.prepare(
      `INSERT INTO devices (id, user_id, short_code, name, status, cloud_epoch, registered_at_utc, last_seen_at_utc)
       VALUES (?, ?, 'A', 'Test', 'ACTIVE', 1, '2026-08-24T00:00:00Z', '2026-08-24T00:00:00Z')`,
    ).bind(deviceId, userId),
    env.POS_DB.prepare(
      `INSERT INTO sessions (token_hash, user_id, device_id, created_at_utc, expires_at_utc)
       VALUES (?, ?, ?, '2026-08-24T00:00:00Z', '2099-01-01T00:00:00Z')`,
    ).bind(tokenHash, userId, deviceId),
  ]);
});

describe("StallPOS v2 sync API", () => {
  it("writes and reads the golden master, sale, duplicate, and void fixtures", async () => {
    expect(await sync(masterBatch)).toMatchObject({ results: masterBatch.operations.map((op) => ({ operationId: op.operationId, status: "ACK" })) });
    await env.POS_DB.prepare(
      `INSERT INTO inventory_levels (user_id, product_id, location_key, location_type, event_id, quantity, updated_at_utc)
       VALUES (?, '50000000-0000-4000-8000-000000000001', 'EVENT:70000000-0000-4000-8000-000000000001',
       'EVENT', '70000000-0000-4000-8000-000000000001', 10, '2026-08-29T02:00:00Z')`,
    ).bind(userId).run();
    expect(await sync(saleBatch)).toMatchObject({ results: [{ status: "ACK" }] });
    expect(await sync(duplicateBatch)).toMatchObject({ results: [{ status: "ACK" }] });
    expect(await sync(voidBatch)).toMatchObject({ results: [{ status: "ACK" }] });

    const transactions = await bootstrap("TRANSACTIONS");
    expect(transactions.data).toEqual([saleBatch.operations[0]!.payload, voidBatch.operations[0]!.payload]);
    const inventory = await bootstrap("INVENTORY");
    expect(inventory.data).toContainEqual(masterBatch.operations.at(-1)!.payload);
    expect(inventory.data).toContainEqual(saleBatch.operations[0]!.payload.inventoryMovements[0]);
    expect(inventory.data).toContainEqual(voidBatch.operations[0]!.payload.inventoryMovements[0]);
    const eventLevel = await env.POS_DB.prepare(
      "SELECT quantity FROM inventory_levels WHERE user_id = ? AND location_key LIKE 'EVENT:%'",
    ).bind(userId).first<{ quantity: number }>();
    expect(eventLevel?.quantity).toBe(10);
  });

  it("rejects invalid and unknown fields at the API boundary", async () => {
    const invalidResponse = await request("/v2/sync/batch", "POST", invalidBatch);
    expect(invalidResponse.status).toBe(400);
    expect(await invalidResponse.json()).toMatchObject({ requestId: invalidBatch.requestId, code: "INVALID_DATA" });

    const unknown = structuredClone(masterBatch) as typeof masterBatch & { invented?: boolean };
    unknown.invented = true;
    const unknownResponse = await request("/v2/sync/batch", "POST", unknown);
    expect(unknownResponse.status).toBe(400);
  });

  it("blocks reuse of an operation ID with different canonical payload", async () => {
    expect(await sync(masterBatch)).toMatchObject({ results: expect.arrayContaining([expect.objectContaining({ status: "ACK" })]) });
    const conflict = structuredClone(masterBatch);
    conflict.operations[0]!.payload.name = "Different";
    const result = await sync(conflict);
    expect(result.results[0]).toMatchObject({ status: "BLOCKED", code: "SERVER_CONFLICT" });
  });

  it("isolates bootstrap data by authenticated user", async () => {
    await sync(masterBatch);
    const otherUser = "11000000-0000-4000-8000-000000000002";
    const otherDevice = "20000000-0000-4000-8000-000000000002";
    const otherToken = "other-token";
    await env.POS_DB.batch([
      env.POS_DB.prepare(
        "INSERT INTO users (id, google_sub, cloud_epoch, created_at_utc) VALUES (?, 'google-other', 1, '2026-08-24T00:00:00Z')",
      ).bind(otherUser),
      env.POS_DB.prepare(
        `INSERT INTO devices (id, user_id, short_code, name, status, cloud_epoch, registered_at_utc, last_seen_at_utc)
         VALUES (?, ?, 'A', 'Other', 'ACTIVE', 1, '2026-08-24T00:00:00Z', '2026-08-24T00:00:00Z')`,
      ).bind(otherDevice, otherUser),
      env.POS_DB.prepare(
        `INSERT INTO sessions (token_hash, user_id, device_id, created_at_utc, expires_at_utc)
         VALUES (?, ?, ?, '2026-08-24T00:00:00Z', '2099-01-01T00:00:00Z')`,
      ).bind(await sha256(otherToken), otherUser, otherDevice),
    ]);
    const response = await request("/v2/bootstrap?group=PRODUCTS", "GET", undefined, otherToken);
    expect((await response.json() as { data: unknown[] }).data).toEqual([]);
  });

  it("keeps deletion tombstones append-only in the separate D1 binding", async () => {
    await env.DELETION_DB.prepare(
      `INSERT INTO deletion_tombstones
       (id, google_sub, former_user_id, scope, deletion_epoch, requested_at_utc)
       VALUES ('d1', 'google-test', ?, 'ACCOUNT', 1, '2026-08-24T00:00:00Z')`,
    ).bind(userId).run();
    await expect(env.DELETION_DB.prepare("DELETE FROM deletion_tombstones WHERE id = 'd1'").run()).rejects.toThrow(/append-only/);
    expect(await env.DELETION_DB.prepare("SELECT COUNT(*) AS count FROM deletion_tombstones").first("count")).toBe(1);
  });
});

async function sync(body: unknown): Promise<{ results: Array<Record<string, unknown>> }> {
  const response = await request("/v2/sync/batch", "POST", body);
  expect(response.status).toBe(200);
  return response.json();
}

async function bootstrap(group: string): Promise<{ data: unknown[] }> {
  const response = await request(`/v2/bootstrap?group=${group}`, "GET");
  expect(response.status).toBe(200);
  return response.json();
}

async function request(path: string, method: string, body?: unknown, bearer = token): Promise<Response> {
  const headers = new Headers({ authorization: `Bearer ${bearer}` });
  if (body !== undefined) headers.set("content-type", "application/json");
  const context = createExecutionContext();
  return worker.fetch(new Request(`https://stallpos.test${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  }), env, context);
}
