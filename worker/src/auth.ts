import { clearUserStatements, latestTombstone, strictlyAfter } from "./account";
import { errorJson, HttpError, json, readJsonObject } from "./http";
import type { AuthContext, GoogleClaims, JsonObject } from "./types";
import { isUuid } from "./validation";

const GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
const ISSUERS = new Set(["accounts.google.com", "https://accounts.google.com"]);
type GoogleJwk = JsonWebKey & { kid?: string; kty?: string; alg?: string; use?: string };
type LoginIntent = "SIGN_IN" | "REENABLE" | "CREATE_AFTER_DELETE";
let jwksCache: { expiresAt: number; keys: GoogleJwk[] } | null = null;

export async function handleGoogleAuth(request: Request, env: Env, requestId: string): Promise<Response> {
  const body = await readJsonObject(request);
  const deviceName = typeof body.deviceName === "string" ? body.deviceName.trim() : "";
  if (Object.keys(body).some((key) => !["idToken", "deviceId", "deviceName", "intent", "forceDevice"].includes(key)) ||
      typeof body.idToken !== "string" || body.idToken.length > 16_384 || !isUuid(body.deviceId) ||
      deviceName.length < 1 || deviceName.length > 100 ||
      (body.intent !== undefined && !["SIGN_IN", "REENABLE", "CREATE_AFTER_DELETE"].includes(String(body.intent))) ||
      (body.forceDevice !== undefined && typeof body.forceDevice !== "boolean")) {
    throw new HttpError(400, "INVALID_DATA", "Google auth request is invalid.");
  }
  const audiences = env.GOOGLE_CLIENT_IDS.split(",").map((value) => value.trim()).filter(Boolean);
  if (audiences.length === 0) throw new HttpError(503, "AUTH_NOT_CONFIGURED", "Google auth is unavailable.");

  let claims: GoogleClaims;
  try {
    claims = await verifyGoogleIdToken(body.idToken, audiences);
  } catch {
    return errorJson(requestId, 401, "INVALID_GOOGLE_TOKEN", "Google ID token verification failed.");
  }

  const intent = (body.intent ?? "SIGN_IN") as LoginIntent;
  const verifiedEmail = claims.email_verified === true && typeof claims.email === "string" &&
    /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(claims.email) && claims.email.length <= 320 ? claims.email : null;
  const tombstone = await latestTombstone(env.DELETION_DB, claims.sub);
  let user = await env.POS_DB.prepare(
    "SELECT id, cloud_epoch, created_at_utc FROM users WHERE google_sub = ?",
  ).bind(claims.sub).first<{ id: string; cloud_epoch: number; created_at_utc: string }>();
  const tombstoneApplies = !!tombstone && (!user || user.created_at_utc <= tombstone.requested_at_utc);

  if (tombstoneApplies && tombstone?.scope === "ACCOUNT") {
    if (intent !== "CREATE_AFTER_DELETE") throw new HttpError(409, "ACCOUNT_DELETED", "Explicit account creation is required.");
    if (user) await checkedBatch(env.POS_DB, clearUserStatements(env.POS_DB, user.id, true));
    user = null;
  } else if (tombstoneApplies && tombstone?.scope === "CLOUD" && user && user.cloud_epoch <= tombstone.deletion_epoch) {
    if (intent !== "REENABLE") throw new HttpError(409, "CLOUD_EPOCH_REVOKED", "Cloud data must be explicitly re-enabled.");
    await checkedBatch(env.POS_DB, [
      ...clearUserStatements(env.POS_DB, user.id, false),
      env.POS_DB.prepare("UPDATE users SET cloud_epoch = ?, deleted_at_utc = NULL WHERE id = ?")
        .bind(tombstone.deletion_epoch + 1, user.id),
    ]);
    user = { ...user, cloud_epoch: tombstone.deletion_epoch + 1 };
  } else if (intent !== "SIGN_IN") {
    throw new HttpError(409, "INVALID_ACCOUNT_STATE", "The requested account transition is not available.");
  }

  if (!user) {
    if (tombstone?.scope === "CLOUD") throw new HttpError(409, "ACCOUNT_UNAVAILABLE", "Account cannot be re-enabled.");
    const id = crypto.randomUUID();
    const epoch = tombstone ? tombstone.deletion_epoch + 1 : 1;
    const createdAtUtc = tombstone ? strictlyAfter(tombstone.requested_at_utc) : new Date().toISOString();
    await env.POS_DB.prepare(
      "INSERT INTO users (id, google_sub, cloud_epoch, created_at_utc, email) VALUES (?, ?, ?, ?, ?)",
    ).bind(id, claims.sub, epoch, createdAtUtc, verifiedEmail).run();
    user = { id, cloud_epoch: epoch, created_at_utc: createdAtUtc };
  } else if (verifiedEmail) {
    await env.POS_DB.prepare("UPDATE users SET email=? WHERE id=?").bind(verifiedEmail, user.id).run();
  }

  const deviceId = body.deviceId as string;
  const conflictingOwner = await env.POS_DB.prepare("SELECT user_id FROM devices WHERE id = ?")
    .bind(deviceId).first<{ user_id: string }>();
  if (conflictingOwner && conflictingOwner.user_id !== user.id) {
    throw new HttpError(409, "DEVICE_CONFLICT", "Device is already registered to another account.");
  }
  const active = await env.POS_DB.prepare("SELECT id FROM devices WHERE user_id = ? AND status = 'ACTIVE' LIMIT 1")
    .bind(user.id).first<{ id: string }>();
  if (active && active.id !== deviceId && body.forceDevice !== true) {
    throw new HttpError(409, "DEVICE_TRANSFER_REQUIRED", "Transfer or force-retire the active device first.");
  }

  const nowUtc = new Date().toISOString();
  const credentials = await credentialsFor(env.POS_DB, user.id, deviceId, nowUtc);
  const statements: D1PreparedStatement[] = [];
  if (active && active.id !== deviceId) {
    statements.push(
      env.POS_DB.prepare("UPDATE devices SET status = 'RETIRED', retired_at_utc = ? WHERE id = ?").bind(nowUtc, active.id),
      env.POS_DB.prepare("UPDATE refresh_credentials SET revoked_at_utc = ? WHERE device_id = ? AND revoked_at_utc IS NULL")
        .bind(nowUtc, active.id),
    );
  }
  statements.push(
    env.POS_DB.prepare(
      `INSERT INTO devices (id, user_id, short_code, name, status, cloud_epoch, registered_at_utc, last_seen_at_utc)
       VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
       ON CONFLICT(id) DO UPDATE SET name=excluded.name, status='ACTIVE', cloud_epoch=excluded.cloud_epoch,
       last_seen_at_utc=excluded.last_seen_at_utc, retired_at_utc=NULL`,
    ).bind(deviceId, user.id, deviceId, deviceName, user.cloud_epoch, nowUtc, nowUtc),
    ...credentials.statements,
    env.POS_DB.prepare(
      `INSERT INTO audit_logs (id, user_id, device_id, request_id, action, occurred_at_utc)
       VALUES (?, ?, ?, ?, 'AUTH_LOGIN', ?)`,
    ).bind(crypto.randomUUID(), user.id, deviceId, requestId, nowUtc),
  );
  await checkedBatch(env.POS_DB, statements);
  return json({ requestId, ...credentials.response, userId: user.id, deviceId, cloudEpoch: user.cloud_epoch }, 200, requestId);
}

export async function handleRefresh(request: Request, env: Env, requestId: string): Promise<Response> {
  const body = await readJsonObject(request);
  if (Object.keys(body).some((key) => !["refreshToken", "deviceId"].includes(key)) ||
      typeof body.refreshToken !== "string" || body.refreshToken.length > 2048 || !isUuid(body.deviceId)) {
    throw new HttpError(400, "INVALID_DATA", "Refresh request is invalid.");
  }
  const hash = await sha256(body.refreshToken);
  const nowUtc = new Date().toISOString();
  const row = await env.POS_DB.prepare(
    `SELECT r.user_id, r.device_id, r.family_id, r.generation, r.expires_at_utc, r.revoked_at_utc,
      u.google_sub, u.cloud_epoch, u.created_at_utc, u.deleted_at_utc, d.status, d.cloud_epoch AS device_epoch
     FROM refresh_credentials r JOIN users u ON u.id=r.user_id JOIN devices d ON d.id=r.device_id
     WHERE r.token_hash=? AND r.device_id=?`,
  ).bind(hash, body.deviceId).first<RefreshRow>();
  if (!row || row.revoked_at_utc || row.expires_at_utc <= nowUtc) throw new HttpError(401, "UNAUTHORIZED", "Refresh credential is invalid.");
  await assertAccountActive(env, row);
  const next = await credentialsFor(env.POS_DB, row.user_id, row.device_id, nowUtc, row.family_id, row.generation + 1);
  try {
    await checkedBatch(env.POS_DB, [
      env.POS_DB.prepare("UPDATE refresh_credentials SET revoked_at_utc=? WHERE token_hash=? AND revoked_at_utc IS NULL").bind(nowUtc, hash),
      env.POS_DB.prepare(
        `INSERT INTO audit_logs (id,user_id,device_id,request_id,action,occurred_at_utc)
         VALUES (?,?,?,?, 'AUTH_REFRESH', ?)`,
      ).bind(hash, row.user_id, row.device_id, requestId, nowUtc),
      ...next.statements,
    ]);
  } catch {
    throw new HttpError(401, "UNAUTHORIZED", "Refresh credential was already consumed.");
  }
  return json({ requestId, ...next.response, userId: row.user_id, deviceId: row.device_id, cloudEpoch: row.cloud_epoch }, 200, requestId);
}

export async function authenticate(request: Request, env: Env): Promise<AuthContext> {
  const authorization = request.headers.get("authorization");
  if (!authorization?.startsWith("Bearer ") || authorization.length > 2048) {
    throw new HttpError(401, "UNAUTHORIZED", "A valid bearer token is required.");
  }
  const tokenHash = await sha256(authorization.slice(7));
  const row = await env.POS_DB.prepare(
    `SELECT s.user_id, s.device_id, u.google_sub, u.cloud_epoch, u.created_at_utc, u.deleted_at_utc,
      d.status, d.cloud_epoch AS device_epoch
     FROM sessions s JOIN users u ON u.id=s.user_id JOIN devices d ON d.id=s.device_id AND d.user_id=s.user_id
     WHERE s.token_hash=? AND s.revoked_at_utc IS NULL AND s.expires_at_utc>?`,
  ).bind(tokenHash, new Date().toISOString()).first<AccountRow>();
  if (!row) throw new HttpError(401, "UNAUTHORIZED", "Session is invalid or expired.");
  await assertAccountActive(env, row);
  return { userId: row.user_id, deviceId: row.device_id, cloudEpoch: row.cloud_epoch, googleSub: row.google_sub };
}

async function assertAccountActive(env: Env, row: AccountRow): Promise<void> {
  const tombstone = await latestTombstone(env.DELETION_DB, row.google_sub);
  if (tombstone && row.created_at_utc <= tombstone.requested_at_utc &&
      (tombstone.scope === "ACCOUNT" || row.cloud_epoch <= tombstone.deletion_epoch)) {
    throw new HttpError(409, tombstone.scope === "ACCOUNT" ? "ACCOUNT_DELETED" : "CLOUD_EPOCH_REVOKED", "Account generation was deleted.");
  }
  if (row.deleted_at_utc) throw new HttpError(409, "CLOUD_EPOCH_REVOKED", "Cloud data was deleted.");
  if (row.status !== "ACTIVE") throw new HttpError(409, "DEVICE_RETIRED", "Device is retired.");
  if (row.device_epoch !== row.cloud_epoch) throw new HttpError(409, "CLOUD_EPOCH_REVOKED", "Cloud epoch is no longer active.");
}

export async function credentialsFor(
  db: D1Database,
  userId: string,
  deviceId: string,
  nowUtc: string,
  familyId = crypto.randomUUID(),
  generation = 0,
) {
  const accessToken = randomToken();
  const refreshToken = randomToken();
  const accessExpiresAtUtc = new Date(new Date(nowUtc).getTime() + 15 * 60_000).toISOString();
  const refreshExpiresAtUtc = new Date(new Date(nowUtc).getTime() + 30 * 24 * 60 * 60_000).toISOString();
  return {
    response: { accessToken, refreshToken, expiresAtUtc: accessExpiresAtUtc, refreshExpiresAtUtc },
    statements: [
      db.prepare("INSERT INTO sessions (token_hash,user_id,device_id,created_at_utc,expires_at_utc) VALUES (?,?,?,?,?)")
        .bind(await sha256(accessToken), userId, deviceId, nowUtc, accessExpiresAtUtc),
      db.prepare(
        `INSERT INTO refresh_credentials
         (token_hash,user_id,device_id,family_id,generation,created_at_utc,expires_at_utc)
         VALUES (?,?,?,?,?,?,?)`,
      ).bind(await sha256(refreshToken), userId, deviceId, familyId, generation, nowUtc, refreshExpiresAtUtc),
    ],
  };
}

type AccountRow = {
  user_id: string; device_id: string; google_sub: string; cloud_epoch: number; created_at_utc: string;
  deleted_at_utc: string | null; status: string; device_epoch: number;
};
type RefreshRow = AccountRow & { family_id: string; generation: number; expires_at_utc: string; revoked_at_utc: string | null };

export async function checkedBatch(db: D1Database, statements: D1PreparedStatement[]) {
  const results = await db.batch(statements);
  if (results.some((result) => !result.success)) throw new Error("D1 batch failed");
}

export async function verifyGoogleIdToken(token: string, audiences: string[]): Promise<GoogleClaims> {
  const parts = token.split(".");
  if (parts.length !== 3 || !parts[0] || !parts[1] || !parts[2]) throw new Error("malformed jwt");
  const header = decodePart(parts[0]) as JsonObject;
  if (header.alg !== "RS256" || typeof header.kid !== "string" || !header.kid) throw new Error("invalid header");
  const keys = await googleKeys();
  const jwk = keys.find((key) => key.kid === header.kid && key.kty === "RSA" &&
    (!key.alg || key.alg === "RS256") && (!key.use || key.use === "sig"));
  if (!jwk) throw new Error("unknown key");
  const key = await crypto.subtle.importKey("jwk", jwk, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
  const verified = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, base64UrlBytes(parts[2]), new TextEncoder().encode(`${parts[0]}.${parts[1]}`));
  if (!verified) throw new Error("invalid signature");

  const payload = decodePart(parts[1]) as Partial<GoogleClaims>;
  const audienceValues = typeof payload.aud === "string" ? [payload.aud] : payload.aud;
  if (!payload.iss || !ISSUERS.has(payload.iss) || !Array.isArray(audienceValues) ||
      !audienceValues.some((audience) => audiences.includes(audience)) ||
      typeof payload.exp !== "number" || !Number.isSafeInteger(payload.exp) || payload.exp <= Date.now() / 1000 ||
      typeof payload.sub !== "string" || payload.sub.length < 1 || payload.sub.length > 255 ||
      (payload.email !== undefined && typeof payload.email !== "string") ||
      (payload.email_verified !== undefined && typeof payload.email_verified !== "boolean")) throw new Error("invalid claims");
  return payload as GoogleClaims;
}

async function googleKeys(): Promise<GoogleJwk[]> {
  if (jwksCache && Date.now() < jwksCache.expiresAt) return jwksCache.keys;
  const response = await fetch(GOOGLE_JWKS_URL, { headers: { accept: "application/json" } });
  if (!response.ok) throw new Error("jwks unavailable");
  const body = await response.json() as { keys?: unknown };
  if (!Array.isArray(body.keys)) throw new Error("invalid jwks");
  const keys = body.keys.filter((key): key is GoogleJwk => !!key && typeof key === "object");
  if (keys.length === 0) throw new Error("empty jwks");
  const maxAge = /max-age=(\d+)/i.exec(response.headers.get("cache-control") ?? "")?.[1];
  jwksCache = { expiresAt: Date.now() + Math.min(Number(maxAge ?? 0), 86_400) * 1000, keys };
  return keys;
}

function decodePart(value: string): unknown {
  return JSON.parse(new TextDecoder().decode(base64UrlBytes(value)));
}

function base64UrlBytes(value: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error("invalid base64url");
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(value.length / 4) * 4, "=");
  return Uint8Array.from(atob(normalized), (char) => char.charCodeAt(0));
}

export function randomToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}
