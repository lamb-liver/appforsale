import { authenticate, handleGoogleAuth } from "./auth";
import { errorJson, HttpError, json, requestIdFor } from "./http";
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
  },
} satisfies ExportedHandler<Env>;
