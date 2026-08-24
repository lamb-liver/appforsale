import * as Sentry from "@sentry/cloudflare";
import { authenticate, handleGoogleAuth, handleRefresh } from "./auth";
import { dashboardAsset } from "./dashboard-assets";
import { authenticateDashboard, handleDashboardLogin, handleDashboardLogout } from "./dashboard-auth";
import { errorJson, HttpError, json, requestIdFor } from "./http";
import { handleClaimTransfer, handleCommitTransfer, handleCreateTransfer, handleDelete, reconcileDeletionTombstones } from "./lifecycle";
import { handleBootstrap, handleSync } from "./sync";
import { handleEventReport, handleEventReports } from "./reports";
import { operationalAlert, rateLimitResponse, runScheduledOps, sentryOptions } from "./ops";

const handler = {
  async fetch(request, env, _ctx): Promise<Response> {
    const requestId = requestIdFor(request);
    try {
      const url = new URL(request.url);
      const asset = dashboardAsset(url.pathname);
      if (request.method === "GET" && asset) return asset;
      if (request.method === "GET" && url.pathname === "/health") {
        return json({ status: "ok" }, 200, requestId);
      }
      const limited = await rateLimitResponse(request, env, requestId);
      if (limited) return limited;
      if (request.method === "POST" && url.pathname === "/v2/auth/google") {
        return await handleGoogleAuth(request, env, requestId);
      }
      if (request.method === "POST" && url.pathname === "/v2/auth/refresh") {
        return await handleRefresh(request, env, requestId);
      }
      if (request.method === "GET" && url.pathname === "/v2/dashboard/config") {
        return json({ requestId, googleClientId: env.DASHBOARD_GOOGLE_CLIENT_ID }, 200, requestId);
      }
      if (request.method === "POST" && url.pathname === "/v2/auth/dashboard") {
        return await handleDashboardLogin(request, env, requestId);
      }
      if (request.method === "DELETE" && url.pathname === "/v2/auth/dashboard") {
        return await handleDashboardLogout(request, env, requestId);
      }
      if (request.method === "GET" && url.pathname === "/v2/reports/events") {
        return await handleEventReports(env, (await authenticateDashboard(request, env)).userId, requestId);
      }
      if (request.method === "GET" && url.pathname.startsWith("/v2/reports/events/")) {
        const eventId = url.pathname.slice("/v2/reports/events/".length);
        return await handleEventReport(env, (await authenticateDashboard(request, env)).userId, eventId, requestId);
      }
      if (request.method === "POST" && url.pathname === "/v2/devices/transfer") {
        return await handleCreateTransfer(request, env, await authenticate(request, env), requestId);
      }
      if (request.method === "POST" && url.pathname === "/v2/devices/transfer/claim") {
        return await handleClaimTransfer(request, env, requestId);
      }
      if (request.method === "POST" && url.pathname === "/v2/devices/transfer/commit") {
        return await handleCommitTransfer(request, env, requestId);
      }
      if (request.method === "DELETE" && url.pathname === "/v2/account/cloud") {
        return await handleDelete(env, await authenticate(request, env), requestId, "CLOUD");
      }
      if (request.method === "DELETE" && url.pathname === "/v2/account") {
        return await handleDelete(env, await authenticate(request, env), requestId, "ACCOUNT");
      }
      if (request.method === "POST" && url.pathname === "/v2/sync/batch") {
        return await handleSync(request, env, await authenticate(request, env));
      }
      if (request.method === "GET" && url.pathname === "/v2/bootstrap") {
        return await handleBootstrap(request, env, await authenticate(request, env), requestId);
      }
      return errorJson(requestId, 404, "NOT_FOUND", "Route not found.");
    } catch (error) {
      if (error instanceof HttpError) {
        if (error.status === 401 || error.status === 403) operationalAlert("AUTHORIZATION_FAILURE", requestId, { status: error.status });
        return errorJson(requestId, error.status, error.code, error.message);
      }
      Sentry.withScope((scope) => {
        scope.setTag("request_id", requestId);
        scope.setTag("ops_alert", "HTTP_5XX");
        Sentry.captureException(error);
      });
      console.error(JSON.stringify({ event: "ops_alert", kind: "HTTP_5XX", requestId }));
      return errorJson(requestId, 500, "INTERNAL_ERROR", "Request could not be completed.");
    }
  },

  async scheduled(_controller, env): Promise<void> {
    const now = new Date().toISOString();
    await env.POS_DB.batch([
      env.POS_DB.prepare("DELETE FROM sessions WHERE expires_at_utc <= ?").bind(now),
      env.POS_DB.prepare("DELETE FROM dashboard_sessions WHERE expires_at_utc <= ?").bind(now),
    ]);
    await reconcileDeletionTombstones(env);
    await runScheduledOps(env);
  },
} satisfies ExportedHandler<Env>;

export default Sentry.withSentry((env) => sentryOptions(env), handler);
