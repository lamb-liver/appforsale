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
  Sentry.withScope((scope) => {
    scope.setTag("ops_alert", kind);
    scope.setTag("request_id", requestId);
    Object.entries(details).forEach(([key, value]) => scope.setExtra(key, value));
    Sentry.captureMessage(`StallPOS ops alert: ${kind}`, "warning");
  });
}

export async function runScheduledOps(env: Env, now = new Date()): Promise<{ deletedAudits: number; emailsSent: number }> {
  const retentionCutoff = new Date(now.getTime() - 180 * DAY_MS).toISOString();
  const deleted = await env.POS_DB.prepare("DELETE FROM audit_logs WHERE occurred_at_utc<?").bind(retentionCutoff).run();
  const emailsSent = await sendInactivityNotifications(env, now);
  return { deletedAudits: deleted.meta.changes, emailsSent };
}

export async function sendInactivityNotifications(
  env: Env,
  now = new Date(),
  sender: Pick<SendEmail, "send"> | undefined = env.EMAIL,
): Promise<number> {
  const from = env.EMAIL_FROM.trim();
  if (!from || !sender) return 0;
  let sent = 0;
  for (const daysBefore of [60, 7] as const) {
    const inactiveDays = 730 - daysBefore;
    const cutoff = new Date(now.getTime() - inactiveDays * DAY_MS).toISOString();
    const lowerBound = daysBefore === 60 ? new Date(now.getTime() - (730 - 7) * DAY_MS).toISOString() : null;
    const candidates = await env.POS_DB.prepare(
      `WITH inactive AS (
         SELECT u.id,u.email,COALESCE(MAX(d.last_seen_at_utc),u.created_at_utc) AS activity_at_utc
         FROM users u LEFT JOIN devices d ON d.user_id=u.id
         WHERE u.deleted_at_utc IS NULL AND u.email IS NOT NULL
         GROUP BY u.id
       )
       SELECT i.id,i.email,i.activity_at_utc FROM inactive i
       WHERE i.activity_at_utc<=? AND (? IS NULL OR i.activity_at_utc>?) AND NOT EXISTS (
         SELECT 1 FROM inactivity_notifications n WHERE n.user_id=i.id
          AND n.days_before_deletion=? AND n.activity_at_utc=i.activity_at_utc
       ) LIMIT 100`,
    ).bind(cutoff, lowerBound, lowerBound, daysBefore).all<{ id: string; email: string; activity_at_utc: string }>();
    for (const user of candidates.results) {
      const subject = `StallPOS 雲端資料將於 ${daysBefore} 天後清理`;
      const text = `你的 StallPOS 雲端帳號已長期未使用。若要保留資料，請在 ${daysBefore} 天內開啟 App 並完成同步。`;
      try {
        await sender.send({
          to: user.email,
          from: { email: from, name: "StallPOS" },
          subject,
          text,
          html: `<p>你的 StallPOS 雲端帳號已長期未使用。</p><p>若要保留資料，請在 <strong>${daysBefore} 天內</strong>開啟 App 並完成同步。</p>`,
        });
        await env.POS_DB.prepare(
          `INSERT INTO inactivity_notifications
           (user_id,days_before_deletion,activity_at_utc,sent_at_utc) VALUES (?,?,?,?)`,
        ).bind(user.id, daysBefore, user.activity_at_utc, now.toISOString()).run();
        sent += 1;
      } catch {
        operationalAlert("INACTIVITY_EMAIL_FAILED", crypto.randomUUID(), { daysBefore });
      }
    }
  }
  return sent;
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
