import { errorJson, HttpError, json, readJsonObject } from "./http";
import type { AuthContext, GoogleClaims, JsonObject } from "./types";
import { isUuid } from "./validation";

const GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
const ISSUERS = new Set(["accounts.google.com", "https://accounts.google.com"]);
type GoogleJwk = JsonWebKey & { kid?: string; kty?: string; alg?: string; use?: string };
let jwksCache: { expiresAt: number; keys: GoogleJwk[] } | null = null;

export async function handleGoogleAuth(request: Request, env: Env, requestId: string): Promise<Response> {
  const body = await readJsonObject(request);
  const allowed = new Set(Object.keys(body));
  if ([...allowed].some((key) => !["idToken", "deviceId", "deviceName"].includes(key)) ||
      typeof body.idToken !== "string" || body.idToken.length > 16_384 ||
      !isUuid(body.deviceId) || typeof body.deviceName !== "string" ||
      body.deviceName.trim().length < 1 || body.deviceName.trim().length > 100) {
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

  const now = new Date();
  const nowUtc = now.toISOString();
  const proposedUserId = crypto.randomUUID();
  await env.POS_DB.prepare(
    "INSERT OR IGNORE INTO users (id, google_sub, cloud_epoch, created_at_utc) VALUES (?, ?, 1, ?)",
  ).bind(proposedUserId, claims.sub, nowUtc).run();
  const user = await env.POS_DB.prepare(
    "SELECT id, cloud_epoch FROM users WHERE google_sub = ? AND deleted_at_utc IS NULL",
  ).bind(claims.sub).first<{ id: string; cloud_epoch: number }>();
  if (!user) throw new HttpError(403, "ACCOUNT_UNAVAILABLE", "Account is unavailable.");

  const accessToken = randomToken();
  const tokenHash = await sha256(accessToken);
  const expiresAtUtc = new Date(now.getTime() + 15 * 60_000).toISOString();
  const deviceId = body.deviceId;
  const deviceName = body.deviceName.trim();
  const existingDevice = await env.POS_DB.prepare("SELECT user_id FROM devices WHERE id = ?")
    .bind(deviceId).first<{ user_id: string }>();
  if (existingDevice && existingDevice.user_id !== user.id) {
    throw new HttpError(409, "DEVICE_CONFLICT", "Device is already registered to another account.");
  }
  const results = await env.POS_DB.batch([
    env.POS_DB.prepare(
      `INSERT INTO devices (id, user_id, short_code, name, status, cloud_epoch, registered_at_utc, last_seen_at_utc)
       VALUES (?, ?, 'A', ?, 'ACTIVE', ?, ?, ?)
       ON CONFLICT(id) DO UPDATE SET name = excluded.name, last_seen_at_utc = excluded.last_seen_at_utc`,
    ).bind(deviceId, user.id, deviceName, user.cloud_epoch, nowUtc, nowUtc),
    env.POS_DB.prepare(
      "INSERT INTO sessions (token_hash, user_id, device_id, created_at_utc, expires_at_utc) VALUES (?, ?, ?, ?, ?)",
    ).bind(tokenHash, user.id, deviceId, nowUtc, expiresAtUtc),
    env.POS_DB.prepare(
      `INSERT INTO audit_logs (id, user_id, device_id, request_id, action, occurred_at_utc)
       VALUES (?, ?, ?, ?, 'AUTH_LOGIN', ?)`,
    ).bind(crypto.randomUUID(), user.id, deviceId, requestId, nowUtc),
  ]);
  if (results.some((result) => !result.success)) throw new Error("auth batch failed");
  return json({
    requestId,
    accessToken,
    expiresAtUtc,
    userId: user.id,
    deviceId,
    cloudEpoch: user.cloud_epoch,
  }, 200, requestId);
}

export async function authenticate(request: Request, env: Env): Promise<AuthContext> {
  const authorization = request.headers.get("authorization");
  if (!authorization?.startsWith("Bearer ") || authorization.length > 2048) {
    throw new HttpError(401, "UNAUTHORIZED", "A valid bearer token is required.");
  }
  const token = authorization.slice(7);
  if (!token) throw new HttpError(401, "UNAUTHORIZED", "A valid bearer token is required.");
  const tokenHash = await sha256(token);
  const row = await env.POS_DB.prepare(
    `SELECT s.user_id, s.device_id, u.cloud_epoch
     FROM sessions s
     JOIN users u ON u.id = s.user_id
     JOIN devices d ON d.id = s.device_id AND d.user_id = s.user_id
     WHERE s.token_hash = ? AND s.revoked_at_utc IS NULL AND s.expires_at_utc > ?
       AND u.deleted_at_utc IS NULL AND d.status = 'ACTIVE'`,
  ).bind(tokenHash, new Date().toISOString()).first<{ user_id: string; device_id: string; cloud_epoch: number }>();
  if (!row) throw new HttpError(401, "UNAUTHORIZED", "Session is invalid or expired.");
  return { userId: row.user_id, deviceId: row.device_id, cloudEpoch: row.cloud_epoch };
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
  const key = await crypto.subtle.importKey(
    "jwk",
    jwk,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"],
  );
  const verified = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    key,
    base64UrlBytes(parts[2]),
    new TextEncoder().encode(`${parts[0]}.${parts[1]}`),
  );
  if (!verified) throw new Error("invalid signature");

  const payload = decodePart(parts[1]) as Partial<GoogleClaims>;
  const audienceValues = typeof payload.aud === "string" ? [payload.aud] : payload.aud;
  if (!payload.iss || !ISSUERS.has(payload.iss) || !Array.isArray(audienceValues) ||
      !audienceValues.some((audience) => audiences.includes(audience)) ||
      typeof payload.exp !== "number" || !Number.isSafeInteger(payload.exp) || payload.exp <= Date.now() / 1000 ||
      typeof payload.sub !== "string" || payload.sub.length < 1 || payload.sub.length > 255) {
    throw new Error("invalid claims");
  }
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

function randomToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}
