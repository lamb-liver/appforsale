import type { JsonObject } from "./types";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export const MAX_JSON_BYTES = 1_048_576;

export function requestIdFor(request: Request): string {
  const supplied = request.headers.get("x-request-id")?.toLowerCase();
  return supplied && UUID.test(supplied) ? supplied : crypto.randomUUID();
}

export async function readJsonObject(request: Request): Promise<JsonObject> {
  if (!request.headers.get("content-type")?.toLowerCase().startsWith("application/json")) {
    throw new HttpError(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json.");
  }
  const declared = Number(request.headers.get("content-length") ?? NaN);
  if (Number.isFinite(declared) && declared > MAX_JSON_BYTES) {
    throw new HttpError(413, "PAYLOAD_TOO_LARGE", "JSON body is too large.");
  }
  const text = await readUtf8Capped(request, MAX_JSON_BYTES);
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    throw new HttpError(400, "INVALID_JSON", "Request body is not valid JSON.");
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new HttpError(400, "INVALID_DATA", "Request body must be a JSON object.");
  }
  return value as JsonObject;
}

async function readUtf8Capped(request: Request, maxBytes: number): Promise<string> {
  const body = request.body;
  if (!body) return "";
  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel();
      throw new HttpError(413, "PAYLOAD_TOO_LARGE", "JSON body is too large.");
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(bytes);
}

export const SECURITY_HEADERS = {
  "cache-control": "no-store",
  "x-content-type-options": "nosniff",
  "x-frame-options": "DENY",
  "referrer-policy": "no-referrer",
  "content-security-policy": "default-src 'none'; frame-ancestors 'none'",
  "permissions-policy": "camera=(), microphone=(), geolocation=()",
};

export function json(data: unknown, status = 200, requestId?: string): Response {
  const headers = new Headers({
    "content-type": "application/json; charset=utf-8",
    ...SECURITY_HEADERS,
  });
  if (requestId) headers.set("x-request-id", requestId);
  return new Response(JSON.stringify(data), { status, headers });
}

export function errorJson(requestId: string, status: number, code: string, message: string): Response {
  return json({ requestId, code, message }, status, requestId);
}

export class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}
