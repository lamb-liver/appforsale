import * as Sentry from "@sentry/cloudflare";
import { sha256 } from "./auth";
import { errorJson } from "./http";

const DAY_MS = 24 * 60 * 60_000;

export async function rateLimitResponse(request: Request, env: Env, requestId: string): Promise<Response | null> {
  const path = new URL(request.url).pathname;
  if (!path.startsWith("/v2/")) return null;
  const limiter = path.startsWith("/v2/auth/") ? env.AUTH_RATE_LIMITER : env.API_RATE_LIMITER;
  if (!limiter) return null;
  const actor = request.headers.get("cf-connecting-ip") ?? "unknown";
  const result = await limiter.limit({ key: await sha256(`${actor}:${path.split("/").slice(0, 4).join("/")}`) });
  if (result.success) return null;
  operationalAlert("RATE_LIMIT", requestId);
  const response = errorJson(requestId, 429, "RATE_LIMITED", "Too many requests. Try again later.");
  response.headers.set("retry-after", "60");
  return response;
}

export function operationalAlert(kind: string, requestId: string, details: Record<string, string | number | boolean> = {}) {
  console.warn(JSON.stringify({ event: "ops_alert", kind, requestId, ...details }));
  Sentry.withScope((scope) => {
    scope.setTag("ops_alert", kind);
    scope.setTag("request_id", requestId);
    Object.entries(details).forEach(([key, value]) => scope.setTag(key, String(value)));
    Sentry.captureMessage(`StallPOS ops alert: ${kind}`, "warning");
  });
}

export async function runScheduledOps(env: Env, now = new Date()): Promise<{ deletedAudits: number }> {
  const retentionCutoff = new Date(now.getTime() - 180 * DAY_MS).toISOString();
  const deleted = await env.POS_DB.prepare("DELETE FROM audit_logs WHERE occurred_at_utc<?").bind(retentionCutoff).run();
  return { deletedAudits: deleted.meta.changes };
}

export function sentryOptions(env: Env): Sentry.CloudflareOptions {
  return {
    dsn: env.SENTRY_DSN || undefined,
    enabled: Boolean(env.SENTRY_DSN),
    sendDefaultPii: false,
    tracesSampleRate: 0,
    beforeSend(event) {
      event.user = undefined;
      event.request = undefined;
      event.breadcrumbs = undefined;
      event.extra = undefined;
      return event;
    },
  };
}
