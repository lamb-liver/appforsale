import { authenticate, handleGoogleAuth, handleRefresh } from "./auth";
import { errorJson, HttpError, json, requestIdFor } from "./http";
import { handleClaimTransfer, handleCommitTransfer, handleCreateTransfer, handleDelete, reconcileDeletionTombstones } from "./lifecycle";
import { handleBootstrap, handleSync } from "./sync";

export default {
  async fetch(request, env, _ctx): Promise<Response> {
    const requestId = requestIdFor(request);
    try {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/health") {
        return json({ status: "ok" }, 200, requestId);
      }
      if (request.method === "POST" && url.pathname === "/v2/auth/google") {
        return await handleGoogleAuth(request, env, requestId);
      }
      if (request.method === "POST" && url.pathname === "/v2/auth/refresh") {
        return await handleRefresh(request, env, requestId);
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
      if (error instanceof HttpError) return errorJson(requestId, error.status, error.code, error.message);
      return errorJson(requestId, 500, "INTERNAL_ERROR", "Request could not be completed.");
    }
  },

  async scheduled(_controller, env): Promise<void> {
    await env.POS_DB.prepare("DELETE FROM sessions WHERE expires_at_utc <= ?").bind(new Date().toISOString()).run();
    await reconcileDeletionTombstones(env);
  },
} satisfies ExportedHandler<Env>;
