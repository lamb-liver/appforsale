import { createExecutionContext, env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import worker from "../src/index";
import { resetPosDb } from "./db";

const audience = "stallpos-test.apps.googleusercontent.com";
const deviceId = "20000000-0000-4000-8000-000000000099";

afterEach(() => vi.unstubAllGlobals());
beforeEach(() => resetPosDb(env.POS_DB));

describe("Google ID token verification", () => {
  it("trusts sub only after a valid Google JWKS signature and claims", async () => {
    const key = await rsaKey("google-key");
    mockJwks(key.publicJwk);
    const idToken = await jwt(key.privateKey, "google-key", validClaims());
    const response = await auth(idToken);

    expect(response.status).toBe(200);
    const body = await response.json() as Record<string, unknown>;
    expect(body).toMatchObject({ deviceId, cloudEpoch: 1 });
    expect(typeof body.accessToken).toBe("string");
    expect(typeof body.refreshToken).toBe("string");
    expect(await env.POS_DB.prepare("SELECT google_sub FROM users").first("google_sub")).toBe("google-sub-123");
  });

  it("rejects forged claims with an invalid signature", async () => {
    const trusted = await rsaKey("trusted");
    const attacker = await rsaKey("attacker");
    mockJwks(trusted.publicJwk);
    const forged = await jwt(attacker.privateKey, "trusted", validClaims());
    expect((await auth(forged)).status).toBe(401);
    expect(await env.POS_DB.prepare("SELECT COUNT(*) AS count FROM users").first("count")).toBe(0);
  });

  it.each([
    ["wrong audience", { ...validClaims(), aud: "attacker.apps.googleusercontent.com" }],
    ["wrong issuer", { ...validClaims(), iss: "https://attacker.example" }],
    ["expired", { ...validClaims(), exp: Math.floor(Date.now() / 1000) - 1 }],
  ])("rejects %s", async (_name, claims) => {
    const key = await rsaKey("google-key");
    mockJwks(key.publicJwk);
    expect((await auth(await jwt(key.privateKey, "google-key", claims))).status).toBe(401);
  });

  it("rejects alg none, unknown kid, and unavailable JWKS", async () => {
    const none = `${encode({ alg: "none", kid: "x" })}.${encode(validClaims())}.x`;
    expect((await auth(none)).status).toBe(401);

    const key = await rsaKey("google-key");
    mockJwks(key.publicJwk);
    expect((await auth(await jwt(key.privateKey, "wrong-kid", validClaims()))).status).toBe(401);

    vi.stubGlobal("fetch", vi.fn(async () => new Response("unavailable", { status: 503 })));
    expect((await auth(await jwt(key.privateKey, "google-key", validClaims()))).status).toBe(401);
  });

  it("rotates refresh credentials and rejects reuse", async () => {
    const key = await rsaKey("refresh-key");
    mockJwks(key.publicJwk);
    const login = await auth(await jwt(key.privateKey, "refresh-key", validClaims("google-refresh")));
    const first = await login.json() as { refreshToken: string };
    const rotated = await request("/v2/auth/refresh", {
      refreshToken: first.refreshToken,
      deviceId,
    });
    expect(rotated.status).toBe(200);
    expect((await rotated.json() as { refreshToken: string }).refreshToken).not.toBe(first.refreshToken);
    expect((await request("/v2/auth/refresh", { refreshToken: first.refreshToken, deviceId })).status).toBe(401);
  });

  it("requires transfer or explicit force before retiring the active device", async () => {
    const key = await rsaKey("device-key");
    mockJwks(key.publicJwk);
    const idToken = await jwt(key.privateKey, "device-key", validClaims("google-device"));
    const first = await auth(idToken);
    const firstAccess = (await first.json() as { accessToken: string }).accessToken;
    const replacement = "20000000-0000-4000-8000-000000000100";
    expect((await auth(idToken, replacement)).status).toBe(409);
    expect((await auth(idToken, replacement, { forceDevice: true })).status).toBe(200);
    const old = await request("/v2/bootstrap?group=PRODUCTS", undefined, firstAccess, "GET");
    expect(old.status).toBe(409);
    expect(await old.json()).toMatchObject({ code: "DEVICE_RETIRED" });
  });

  it("requires explicit creation after account deletion and creates a newer generation", async () => {
    const key = await rsaKey("delete-key");
    mockJwks(key.publicJwk);
    const idToken = await jwt(key.privateKey, "delete-key", validClaims("google-account-delete"));
    const first = await auth(idToken);
    const session = await first.json() as { accessToken: string; userId: string };
    expect((await request("/v2/account", undefined, session.accessToken, "DELETE")).status).toBe(200);
    expect((await auth(idToken)).status).toBe(409);
    const recreated = await auth(idToken, deviceId, { intent: "CREATE_AFTER_DELETE" });
    expect(recreated.status).toBe(200);
    expect((await recreated.json() as { userId: string }).userId).not.toBe(session.userId);
    const tombstone = await env.DELETION_DB.prepare(
      "SELECT requested_at_utc FROM deletion_tombstones WHERE google_sub='google-account-delete' ORDER BY requested_at_utc DESC LIMIT 1",
    ).first<string>("requested_at_utc");
    const created = await env.POS_DB.prepare("SELECT created_at_utc FROM users WHERE google_sub='google-account-delete'")
      .first<string>("created_at_utc");
    expect(created! > tombstone!).toBe(true);
  });
});

async function auth(idToken: string, id = deviceId, extra: Record<string, unknown> = {}): Promise<Response> {
  return request("/v2/auth/google", { idToken, deviceId: id, deviceName: "Pixel", ...extra });
}

async function request(path: string, body?: unknown, bearer?: string, method = "POST"): Promise<Response> {
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

function validClaims(sub = "google-sub-123") {
  return {
    sub,
    iss: "https://accounts.google.com",
    aud: audience,
    exp: Math.floor(Date.now() / 1000) + 3600,
  };
}

async function rsaKey(kid: string): Promise<{ privateKey: CryptoKey; publicJwk: JsonWebKey }> {
  const pair = await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"],
  ) as CryptoKeyPair;
  const publicJwk = await crypto.subtle.exportKey("jwk", pair.publicKey) as JsonWebKey;
  Object.assign(publicJwk, { kid, alg: "RS256", use: "sig" });
  return { privateKey: pair.privateKey, publicJwk };
}

async function jwt(privateKey: CryptoKey, kid: string, claims: Record<string, unknown>): Promise<string> {
  const header = encode({ alg: "RS256", typ: "JWT", kid });
  const payload = encode(claims);
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    privateKey,
    new TextEncoder().encode(`${header}.${payload}`),
  );
  return `${header}.${payload}.${base64Url(new Uint8Array(signature))}`;
}

function mockJwks(publicJwk: JsonWebKey) {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ keys: [publicJwk] }), {
    headers: { "content-type": "application/json", "cache-control": "public, max-age=0" },
  })));
}

function encode(value: unknown): string {
  return base64Url(new TextEncoder().encode(JSON.stringify(value)));
}

function base64Url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
