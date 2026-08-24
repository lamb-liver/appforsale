import type { JsonObject } from "./types";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const MAX_JSON_BYTES = 1_048_576;

export function requestIdFor(request: Request): string {
  const supplied = request.headers.get("x-request-id")?.toLowerCase();
  return supplied && UUID.test(supplied) ? supplied : crypto.randomUUID();
}

export async function readJsonObject(request: Request): Promise<JsonObject> {
  if (!request.headers.get("content-type")?.toLowerCase().startsWith("application/json")) {
    throw new HttpError(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json.");
  }
  const declared = Number(request.headers.get("content-length") ?? 0);
  if (Number.isFinite(declared) && declared > MAX_JSON_BYTES) {
    throw new HttpError(413, "PAYLOAD_TOO_LARGE", "JSON body is too large.");
  }
  const text = await request.text();
  if (new TextEncoder().encode(text).byteLength > MAX_JSON_BYTES) {
    throw new HttpError(413, "PAYLOAD_TOO_LARGE", "JSON body is too large.");
  }
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

export function json(data: unknown, status = 200, requestId?: string): Response {
  const headers = new Headers({
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
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
