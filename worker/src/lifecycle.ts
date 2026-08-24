import { clearUserStatements } from "./account";
import { checkedBatch, credentialsFor, randomToken, sha256 } from "./auth";
import { HttpError, json, readJsonObject } from "./http";
import type { AuthContext } from "./types";
import { isUuid } from "./validation";

export async function handleCreateTransfer(request: Request, env: Env, auth: AuthContext, requestId: string): Promise<Response> {
  const body = await readJsonObject(request);
  if (Object.keys(body).length > 0) throw new HttpError(400, "INVALID_DATA", "Transfer request must be empty.");
  const transferToken = randomToken();
  const now = new Date();
  await env.POS_DB.prepare(
    `INSERT INTO device_transfers
     (id,user_id,source_device_id,token_hash,status,created_at_utc,expires_at_utc)
     VALUES (?,?,?,?,'REQUESTED',?,?)`,
  ).bind(crypto.randomUUID(), auth.userId, auth.deviceId, await sha256(transferToken), now.toISOString(),
    new Date(now.getTime() + 10 * 60_000).toISOString()).run();
  return json({ requestId, transferToken, expiresAtUtc: new Date(now.getTime() + 10 * 60_000).toISOString() }, 200, requestId);
}

export async function handleClaimTransfer(request: Request, env: Env, requestId: string): Promise<Response> {
  const body = await readJsonObject(request);
  const deviceName = typeof body.deviceName === "string" ? body.deviceName.trim() : "";
  if (Object.keys(body).some((key) => !["transferToken", "deviceId", "deviceName"].includes(key)) ||
      typeof body.transferToken !== "string" || body.transferToken.length > 2048 || !isUuid(body.deviceId) ||
      deviceName.length < 1 || deviceName.length > 100) throw new HttpError(400, "INVALID_DATA", "Transfer claim is invalid.");
  const nowUtc = new Date().toISOString();
  const tokenHash = await sha256(body.transferToken);
  const commitToken = await transferCommitToken(env.TRANSFER_TOKEN_SECRET, body.transferToken);
  const transfer = await env.POS_DB.prepare(
    `SELECT id,user_id,source_device_id,status,target_device_id,target_device_name FROM device_transfers
     WHERE token_hash=? AND status IN ('REQUESTED','CLAIMED') AND expires_at_utc>?`,
  ).bind(tokenHash, nowUtc).first<TransferClaimRow>();
  if (!transfer || (transfer.status === "CLAIMED" &&
      (transfer.target_device_id !== body.deviceId || transfer.target_device_name !== deviceName))) {
    throw new HttpError(409, "TRANSFER_INVALID", "Transfer token is invalid or expired.");
  }
  const owner = await env.POS_DB.prepare("SELECT user_id FROM devices WHERE id=?").bind(body.deviceId).first<{ user_id: string }>();
  if (owner && owner.user_id !== transfer.user_id) throw new HttpError(409, "DEVICE_CONFLICT", "Device belongs to another account.");
  if (transfer.status === "REQUESTED") {
    const claimed = await env.POS_DB.prepare(
      `UPDATE device_transfers SET target_device_id=?,target_device_name=?,commit_token_hash=?,status='CLAIMED',claimed_at_utc=?
       WHERE id=? AND status='REQUESTED'`,
    ).bind(body.deviceId, deviceName, await sha256(commitToken), nowUtc, transfer.id).run();
    if (claimed.meta.changes !== 1) {
      const winner = await env.POS_DB.prepare(
        "SELECT status,target_device_id,target_device_name FROM device_transfers WHERE id=?",
      ).bind(transfer.id).first<Pick<TransferClaimRow, "status" | "target_device_id" | "target_device_name">>();
      if (winner?.status !== "CLAIMED" || winner.target_device_id !== body.deviceId || winner.target_device_name !== deviceName) {
        throw new HttpError(409, "TRANSFER_INVALID", "Transfer token was already claimed.");
      }
    }
  }
  return json({ requestId, commitToken, bootstrap: await bootstrapFor(env.POS_DB, transfer.user_id) }, 200, requestId);
}

export async function handleCommitTransfer(request: Request, env: Env, requestId: string): Promise<Response> {
  const body = await readJsonObject(request);
  if (Object.keys(body).some((key) => key !== "commitToken") || typeof body.commitToken !== "string" || body.commitToken.length > 2048) {
    throw new HttpError(400, "INVALID_DATA", "Transfer commit is invalid.");
  }
  const nowUtc = new Date().toISOString();
  const transfer = await env.POS_DB.prepare(
    `SELECT t.id,t.user_id,t.source_device_id,t.target_device_id,t.target_device_name,u.cloud_epoch
     FROM device_transfers t JOIN users u ON u.id=t.user_id
     WHERE t.commit_token_hash=? AND t.status='CLAIMED' AND t.expires_at_utc>?`,
  ).bind(await sha256(body.commitToken), nowUtc).first<{
    id: string; user_id: string; source_device_id: string; target_device_id: string; target_device_name: string; cloud_epoch: number;
  }>();
  if (!transfer?.target_device_id || !transfer.target_device_name) throw new HttpError(409, "TRANSFER_INVALID", "Transfer is not ready.");
  const credentials = await credentialsFor(env.POS_DB, transfer.user_id, transfer.target_device_id, nowUtc);
  await checkedBatch(env.POS_DB, [
    env.POS_DB.prepare("UPDATE device_transfers SET status='COMMITTED',committed_at_utc=? WHERE id=? AND status='CLAIMED'")
      .bind(nowUtc, transfer.id),
    env.POS_DB.prepare(
      `INSERT INTO audit_logs (id,user_id,device_id,request_id,action,occurred_at_utc)
       VALUES (?,?,?,?, 'DEVICE_TRANSFER_COMMIT', ?)`,
    ).bind(transfer.id, transfer.user_id, transfer.target_device_id, requestId, nowUtc),
    env.POS_DB.prepare("UPDATE devices SET status='RETIRED',retired_at_utc=? WHERE id=? AND status='ACTIVE'")
      .bind(nowUtc, transfer.source_device_id),
    env.POS_DB.prepare("UPDATE refresh_credentials SET revoked_at_utc=? WHERE device_id=? AND revoked_at_utc IS NULL")
      .bind(nowUtc, transfer.source_device_id),
    env.POS_DB.prepare(
      `INSERT INTO devices (id,user_id,short_code,name,status,cloud_epoch,registered_at_utc,last_seen_at_utc)
       VALUES (?,?,?,?,'ACTIVE',?,?,?)
       ON CONFLICT(id) DO UPDATE SET name=excluded.name,status='ACTIVE',cloud_epoch=excluded.cloud_epoch,
       last_seen_at_utc=excluded.last_seen_at_utc,retired_at_utc=NULL`,
    ).bind(transfer.target_device_id, transfer.user_id, transfer.target_device_id, transfer.target_device_name,
      transfer.cloud_epoch, nowUtc, nowUtc),
    ...credentials.statements,
  ]);
  return json({ requestId, ...credentials.response, userId: transfer.user_id, deviceId: transfer.target_device_id,
    cloudEpoch: transfer.cloud_epoch }, 200, requestId);
}

export async function handleDelete(env: Env, auth: AuthContext, requestId: string, scope: "CLOUD" | "ACCOUNT"): Promise<Response> {
  const nowUtc = new Date().toISOString();
  await env.DELETION_DB.prepare(
    `INSERT INTO deletion_tombstones
     (id,google_sub,former_user_id,scope,deletion_epoch,requested_at_utc) VALUES (?,?,?,?,?,?)`,
  ).bind(crypto.randomUUID(), auth.googleSub, auth.userId, scope, auth.cloudEpoch, nowUtc).run();
  try {
    const statements = clearUserStatements(env.POS_DB, auth.userId, scope === "ACCOUNT");
    if (scope === "CLOUD") {
      statements.push(env.POS_DB.prepare("UPDATE users SET deleted_at_utc=?,email=NULL WHERE id=?").bind(nowUtc, auth.userId));
    }
    await checkedBatch(env.POS_DB, statements);
    return json({ requestId, status: "DELETED", scope }, 200, requestId);
  } catch {
    return json({ requestId, status: "DELETION_PENDING", scope }, 202, requestId);
  }
}

export async function reconcileDeletionTombstones(env: Env): Promise<void> {
  const tombstones = await env.DELETION_DB.prepare(
    `SELECT google_sub,scope,deletion_epoch,requested_at_utc FROM deletion_tombstones
     ORDER BY requested_at_utc`,
  ).all<{ google_sub: string; scope: "CLOUD" | "ACCOUNT"; deletion_epoch: number; requested_at_utc: string }>();
  for (const tombstone of tombstones.results) {
    const user = await env.POS_DB.prepare("SELECT id,cloud_epoch,created_at_utc FROM users WHERE google_sub=?")
      .bind(tombstone.google_sub).first<{ id: string; cloud_epoch: number; created_at_utc: string }>();
    if (!user || user.created_at_utc > tombstone.requested_at_utc ||
        (tombstone.scope === "CLOUD" && user.cloud_epoch > tombstone.deletion_epoch)) continue;
    const statements = clearUserStatements(env.POS_DB, user.id, tombstone.scope === "ACCOUNT");
    if (tombstone.scope === "CLOUD") {
      statements.push(env.POS_DB.prepare("UPDATE users SET deleted_at_utc=?,email=NULL WHERE id=?")
        .bind(tombstone.requested_at_utc, user.id));
    }
    await checkedBatch(env.POS_DB, statements);
  }
}

async function bootstrapFor(db: D1Database, userId: string): Promise<Record<string, unknown[]>> {
  const groups = {
    products: ["categories", "products"], bundles: ["bundles"], events: ["events"],
    inventory: ["inventory_movements"], transactions: ["sales", "voids"],
  } as const;
  const result: Record<string, unknown[]> = {};
  for (const [group, tables] of Object.entries(groups)) {
    result[group] = [];
    for (const table of tables) {
      const rows = await db.prepare(`SELECT payload_json FROM ${table} WHERE user_id=? ORDER BY rowid`)
        .bind(userId).all<{ payload_json: string }>();
      result[group]!.push(...rows.results.map((row) => JSON.parse(row.payload_json)));
    }
  }
  const levels = await db.prepare(
    `SELECT product_id,location_type,event_id,quantity,updated_at_utc
     FROM inventory_levels WHERE user_id=? ORDER BY product_id,location_key`,
  ).bind(userId).all<{ product_id: string; location_type: string; event_id: string | null; quantity: number; updated_at_utc: string }>();
  result.inventoryLevels = levels.results.map((row) => ({
    productId: row.product_id,
    locationType: row.location_type,
    eventId: row.event_id,
    quantity: row.quantity,
    updatedAtUtc: row.updated_at_utc,
  }));
  return result;
}

type TransferClaimRow = {
  id: string;
  user_id: string;
  source_device_id: string;
  status: "REQUESTED" | "CLAIMED";
  target_device_id: string | null;
  target_device_name: string | null;
};

async function transferCommitToken(secret: string, transferToken: string): Promise<string> {
  if (secret.length < 32) throw new HttpError(503, "TRANSFER_NOT_CONFIGURED", "Device transfer is unavailable.");
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const bytes = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(transferToken)));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
