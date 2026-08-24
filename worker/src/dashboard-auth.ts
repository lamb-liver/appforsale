import { latestTombstone } from "./account";
import { randomToken, sha256, verifyGoogleIdToken } from "./auth";
import { HttpError, json, readJsonObject } from "./http";

const COOKIE_NAME = "stallpos_dashboard";

export async function handleDashboardLogin(request: Request, env: Env, requestId: string): Promise<Response> {
  const body = await readJsonObject(request);
  if (Object.keys(body).some((key) => key !== "idToken") || typeof body.idToken !== "string" || body.idToken.length > 16_384) {
    throw new HttpError(400, "INVALID_DATA", "Dashboard login request is invalid.");
  }
  const clientId = env.DASHBOARD_GOOGLE_CLIENT_ID?.trim() ?? "";
  if (!clientId) throw new HttpError(503, "AUTH_NOT_CONFIGURED", "Dashboard login is unavailable.");
  let sub: string;
  try {
    sub = (await verifyGoogleIdToken(body.idToken, [clientId])).sub;
  } catch {
    throw new HttpError(401, "INVALID_GOOGLE_TOKEN", "Google ID token verification failed.");
  }
  const user = await env.POS_DB.prepare(
    "SELECT id,cloud_epoch,created_at_utc,deleted_at_utc FROM users WHERE google_sub=?",
  ).bind(sub).first<{ id: string; cloud_epoch: number; created_at_utc: string; deleted_at_utc: string | null }>();
  const tombstone = await latestTombstone(env.DELETION_DB, sub);
  if (!user || user.deleted_at_utc || (tombstone && user.created_at_utc <= tombstone.requested_at_utc &&
      (tombstone.scope === "ACCOUNT" || user.cloud_epoch <= tombstone.deletion_epoch))) {
    throw new HttpError(409, "ACCOUNT_UNAVAILABLE", "Dashboard account is unavailable.");
  }
  const token = randomToken();
  const now = new Date();
  const expires = new Date(now.getTime() + 8 * 60 * 60_000);
  const results = await env.POS_DB.batch([
    env.POS_DB.prepare("INSERT INTO dashboard_sessions (token_hash,user_id,created_at_utc,expires_at_utc) VALUES (?,?,?,?)")
      .bind(await sha256(token), user.id, now.toISOString(), expires.toISOString()),
    env.POS_DB.prepare(
      `INSERT INTO audit_logs (id,user_id,device_id,request_id,action,occurred_at_utc)
       VALUES (?, ?, NULL, ?, 'DASHBOARD_LOGIN', ?)`,
    ).bind(crypto.randomUUID(), user.id, requestId, now.toISOString()),
  ]);
  if (results.some((result) => !result.success)) throw new Error("dashboard auth batch failed");
  const response = json({ requestId, expiresAtUtc: expires.toISOString() }, 200, requestId);
  response.headers.set("set-cookie", `${COOKIE_NAME}=${token}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=28800`);
  return response;
}

export async function handleDashboardLogout(request: Request, env: Env, requestId: string): Promise<Response> {
  const token = cookieToken(request);
  if (token) await env.POS_DB.prepare("DELETE FROM dashboard_sessions WHERE token_hash=?").bind(await sha256(token)).run();
  const response = json({ requestId, status: "SIGNED_OUT" }, 200, requestId);
  response.headers.set("set-cookie", `${COOKIE_NAME}=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0`);
  return response;
}

export async function authenticateDashboard(request: Request, env: Env): Promise<{ userId: string }> {
  const token = cookieToken(request);
  if (!token) throw new HttpError(401, "UNAUTHORIZED", "Dashboard sign-in is required.");
  const row = await env.POS_DB.prepare(
    `SELECT d.user_id,u.google_sub,u.cloud_epoch,u.created_at_utc,u.deleted_at_utc
     FROM dashboard_sessions d JOIN users u ON u.id=d.user_id
     WHERE d.token_hash=? AND d.expires_at_utc>?`,
  ).bind(await sha256(token), new Date().toISOString()).first<{
    user_id: string; google_sub: string; cloud_epoch: number; created_at_utc: string; deleted_at_utc: string | null;
  }>();
  if (!row || row.deleted_at_utc) throw new HttpError(401, "UNAUTHORIZED", "Dashboard session is invalid or expired.");
  const tombstone = await latestTombstone(env.DELETION_DB, row.google_sub);
  if (tombstone && row.created_at_utc <= tombstone.requested_at_utc &&
      (tombstone.scope === "ACCOUNT" || row.cloud_epoch <= tombstone.deletion_epoch)) {
    throw new HttpError(409, "CLOUD_EPOCH_REVOKED", "Account generation was deleted.");
  }
  return { userId: row.user_id };
}

function cookieToken(request: Request): string | null {
  const cookie = request.headers.get("cookie");
  if (!cookie || cookie.length > 4096) return null;
  const value = cookie.split(";").map((part) => part.trim()).find((part) => part.startsWith(`${COOKIE_NAME}=`))?.slice(COOKIE_NAME.length + 1);
  return value && /^[A-Za-z0-9_-]{20,200}$/.test(value) ? value : null;
}
