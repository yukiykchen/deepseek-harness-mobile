import { dispatch } from "../../../host-plugin/src/index.js";

// The mock serves the companion plugin's route by calling the plugin's own
// dispatch, so the app is exercised against the real implementation and the two
// cannot drift. Only the Cordis loader is faked, out of the same inventory
// fixture the official pluginInventory/list is served from.

const PHASE_STATE = { pending: 0, loading: 1, active: 2, failed: 3, unloading: 5 };

/** A loader whose entries behave like Cordis entries for enable/disable/reload. */
export function createFakeLoader(inventory) {
  const entries = (inventory.entries ?? []).map((row) => {
    const entry = {
      id: row.entryId,
      disabled: row.enabled === false,
      options: {
        name: row.moduleName,
        group: false,
        // The official inventory carries no config; invent a plausible one so the
        // app's configuration view has something to show.
        config: configFor(row),
      },
      fiber: row.fiberPhase === null ? null : { state: PHASE_STATE[row.fiberPhase] ?? 2 },
      async update(patch) {
        if (Object.hasOwn(patch, "disabled")) {
          entry.disabled = patch.disabled === true;
          entry.fiber = entry.disabled ? null : { state: 2 };
        }
      },
    };
    return entry;
  });
  return { entries: () => entries, await: async () => {} };
}

function configFor(row) {
  if (row.moduleName.includes("llm-")) {
    return { provider: "deepseek", baseUrl: "https://api.deepseek.com", apiKey: "sk-mock-0123456789abcdef" };
  }
  if (row.moduleName.includes("mcp-")) {
    return { servers: [{ name: "memory", command: "npx", args: ["-y", "@mcp/memory"] }], startTimeoutMs: 8000 };
  }
  if (row.moduleName.includes("telemetry")) {
    return { endpoint: "http://127.0.0.1:4318", headers: { authorization: "Bearer mock-otel-token" } };
  }
  return { enabled: row.enabled };
}

/**
 * Handles `/dsh-mobile/*`. Authentication is the mock's own cookie check, which
 * stands in for the real plugin's `connection.requestRejection`.
 */
export async function handleMobileAdmin(req, res, { loader, pathname, authorized, json, readJson }) {
  if (!authorized) {
    return json(res, 401, { ok: false, error: { code: "unauthorized", message: "Sign in to the Host first." } });
  }
  if (req.method === "GET" && pathname === "/dsh-mobile/health") {
    return json(res, 200, { ok: true, value: { protocol: 1 } });
  }
  if (req.method !== "POST" || pathname !== "/dsh-mobile/rpc") {
    return json(res, 404, { ok: false, error: { code: "not-found", message: `No route for ${req.method} ${pathname}.` } });
  }
  const body = await readJson(req);
  const method = String(body?.method ?? "");
  const params = body?.params && typeof body.params === "object" ? body.params : {};
  return json(res, 200, await dispatch(loader, method, params));
}
