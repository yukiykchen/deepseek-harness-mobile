// Unary Remote endpoints (`POST /api/<namespace>/<method>`). Payloads follow the
// Typert wire: `{ "args": { "<parameter>": ... } }` with the exact parameter
// names the generated 0.1.5-rc.1 descriptors declare.
import { randomUUID } from "node:crypto";
import { RemoteError } from "./state.js";
import { pickScenario, TurnRunner } from "./scenarios.js";

const requireArgs = (payload) => {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)
    || Object.keys(payload).length !== 1 || !payload.args || typeof payload.args !== "object") {
    throw new RemoteError("gateway/arguments-invalid", "Remote payload must contain exactly one plain-object args field");
  }
  return payload.args;
};

const requireField = (args, name, endpoint) => {
  if (!Object.hasOwn(args, name)) {
    throw new RemoteError("gateway/arguments-invalid", `${endpoint} requires args.${name}`, { field: name });
  }
  return args[name];
};

/** The official entry shape, projected from loader entries. */
function officialEntries(loader) {
  const PHASE = ["pending", "loading", "active", "failed", null, "unloading"];
  const out = [];
  for (const entry of loader.entries()) {
    if (entry.options?.group) continue;
    out.push({
      entryId: entry.id,
      moduleName: entry.options.name,
      enabled: entry.disabled !== true,
      fiberPhase: entry.fiber == null ? null : (PHASE[entry.fiber.state] ?? null),
    });
  }
  return out;
}

export function createHandlers(host, { runningTurns, speed, pluginLoader }) {
  const handlers = {
    // ---------------------------------------------------------------- session
    "session/list": (args) => {
      // Generated wire name for the reserved empty request is `_request`.
      if (Object.hasOwn(args, "request")) throw new RemoteError("gateway/arguments-invalid", "session/list takes args._request", { field: "request" });
      return { items: host.listSummaries() };
    },
    "session/create": (args) => {
      const request = requireField(args, "request", "session/create");
      const session = host.createSession({ workspaceId: request.workspaceId, cwd: request.cwd, sessionId: request.sessionId });
      return { sessionId: session.id };
    },
    "session/prompt": async (args) => {
      const request = requireField(args, "request", "session/prompt");
      if (typeof request.requestId !== "string" || !request.requestId) {
        throw new RemoteError("gateway/arguments-invalid", "session/prompt requires request.requestId", { field: "requestId" });
      }
      const session = host.requireSession(request.sessionId);
      if (!Array.isArray(request.content) || request.content.length === 0) {
        throw new RemoteError("gateway/arguments-invalid", "prompt content must not be empty");
      }
      const hasText = request.content.some((p) => p.type === "text" && p.text.trim().length > 0);
      const hasImage = request.content.some((p) => p.type === "image");
      if (!hasText && !hasImage) throw new RemoteError("gateway/arguments-invalid", "prompt needs text or an attachment");
      if (session.running) {
        // Real hosts queue; the mock keeps a visible pending queue item until the turn ends.
        const item = { id: randomUUID(), placement: "queued", rpcId: request.requestId, message: { id: randomUUID(), content: request.content.filter((p) => p.type === "text") } };
        session.queue.push(item);
        host.broadcastControl({ type: "queue", sessionId: session.id, items: session.queue });
        return { accepted: true };
      }
      const content = host.admitPromptContent(request.content);
      const imageRefs = content.filter((p) => p.type === "image").map((p) => p.attachment);
      for (const ref of imageRefs) session.referencedAttachments.add(ref.attachmentId);
      const promptText = content.filter((p) => p.type === "text").map((p) => p.text).join("\n");

      session.turn += 1;
      host.appendEvent(session, "turn/start", { turn: session.turn });
      host.appendEvent(session, "user/message", host.userMessage(content, request.requestId, request.clientTimeZone));
      host.touch(session);
      if (!session.projections.has("title")) {
        host.setProjection(session, "title", (promptText.split("\n")[0] || "Image conversation").slice(0, 40));
      }
      host.setRunning(session, true);
      const scenario = pickScenario(host.fixtures.scenarios, promptText);
      const runner = new TurnRunner(host, session, scenario, { promptText, imageRefs, speed });
      runningTurns.set(session.id, runner);
      runner.run().catch((error) => console.error("[mock-host] turn failed", error)).finally(() => {
        if (runningTurns.get(session.id) === runner) runningTurns.delete(session.id);
        if (session.queue.length > 0) {
          const next = session.queue.shift();
          host.broadcastControl({ type: "queue", sessionId: session.id, items: session.queue });
          const text = next.message.content.map((p) => p.text).join("\n");
          void handlers["session/prompt"]({ request: { requestId: next.rpcId, sessionId: session.id, mode: "queue", content: [{ type: "text", text }] } });
        }
      });
      return { accepted: true };
    },
    "session/cancel": (args) => {
      const request = requireField(args, "request", "session/cancel");
      const session = host.requireSession(request.sessionId);
      runningTurns.get(session.id)?.cancel();
      return { cancelled: true };
    },
    "session/rename": (args) => {
      const request = requireField(args, "request", "session/rename");
      const session = host.requireSession(request.sessionId);
      const title = String(request.title ?? "").trim().replace(/\s+/gu, " ");
      if (!title) throw new RemoteError("session/title-invalid", "title must not be empty", { sessionId: session.id });
      host.appendEvent(session, "session/title", { title });
      host.setProjection(session, "title", title);
      return { title, seq: session.seq };
    },
    "session/fork": (args) => {
      const request = requireField(args, "request", "session/fork");
      const source = host.requireSession(request.sessionId);
      const child = host.createSession({ workspaceId: host.workspaces[0]?.workspaceId, cwd: source.cwd });
      for (const record of source.journal) host.appendEvent(child, record.event.type, record.event.data);
      child.blank = source.blank;
      child.lastPromptAt = source.lastPromptAt;
      host.setProjection(child, "title", `${source.projections.get("title")?.value ?? "Untitled"} (fork)`);
      return { sessionId: child.id };
    },
    "session/attachment": (args) => {
      const request = requireField(args, "request", "session/attachment");
      const session = host.requireSession(request.sessionId);
      return host.readAttachment(session, String(request.attachmentId));
    },
    "session/updateQueue": (args) => {
      const request = requireField(args, "request", "session/updateQueue");
      const session = host.requireSession(request.sessionId);
      const index = session.queue.findIndex((item) => item.id === request.itemId);
      if (index < 0) throw new RemoteError("session/queue-item-not-found", "queue item not found", { itemId: request.itemId });
      const action = request.action ?? {};
      if (action.kind === "remove") session.queue.splice(index, 1);
      else if (action.kind === "edit") {
        if (!Array.isArray(action.content) || action.content.some((b) => b.type !== "text")) {
          throw new RemoteError("session/attachment-invalid", "queue edits accept text content only", { reason: "QUEUE_EDIT_NON_TEXT" });
        }
        session.queue[index] = { ...session.queue[index], message: { ...session.queue[index].message, content: action.content } };
      } else if (action.kind === "steer") {
        if (!session.running) throw new RemoteError("session/steer-unavailable", "no active turn to steer", { itemId: request.itemId });
        session.queue[index] = { ...session.queue[index], placement: "steering" };
      } else throw new RemoteError("gateway/arguments-invalid", "unknown queue action");
      host.broadcastControl({ type: "queue", sessionId: session.id, items: session.queue });
      return { updated: true };
    },
    "session/page": (args) => {
      const request = requireField(args, "request", "session/page");
      const session = host.requireSession(request.address?.sessionId);
      return host.page(session, { beforeSeq: request.beforeSeq, maxMessages: request.maxMessages ?? 80 });
    },
    "session/modelCatalog": () => host.fixtures.modelCatalog,
    "session/selectModel": (args) => {
      const request = requireField(args, "request", "session/selectModel");
      const session = host.requireSession(request.sessionId);
      const selected = { provider: request.provider, model: request.model };
      if (request.reasoningEffort) selected.reasoningEffort = request.reasoningEffort;
      const group = host.fixtures.modelCatalog.groups.find((g) => g.id === selected.provider);
      if (!group?.models.some((m) => m.id === selected.model)) {
        throw new RemoteError("session/model-unavailable", `model ${selected.provider}/${selected.model} is not routable`, { sessionId: session.id });
      }
      const previous = session.projections.get("modelSelection")?.value ?? { lastUsed: null, next: null };
      host.setProjection(session, "modelSelection", { lastUsed: previous.lastUsed, next: selected });
      return { selected };
    },
    // ---------------------------------------------------------------- skills / plugins / settings
    "skills/list": (args) => {
      const request = requireField(args, "request", "skills/list");
      host.requireSession(request.sessionId);
      return { skills: host.fixtures.skills };
    },
    // On a real Host the official inventory and the companion admin plugin read the
    // same loader, so a lifecycle change is visible to both. The mock mirrors that by
    // projecting the official snapshot out of the same fake loader.
    "pluginInventory/list": () => ({
      ...host.fixtures.pluginInventory,
      entries: pluginLoader === undefined
        ? host.fixtures.pluginInventory.entries
        : officialEntries(pluginLoader),
    }),
    "credentials/describe": (args) => {
      const refs = requireField(args, "refs", "credentials/describe");
      const result = {};
      for (const ref of refs) result[ref] = { configured: ref === "DEEPSEEK_API_KEY", writable: true, source: "env" };
      return result;
    },
    "credentials/set": (args) => {
      requireField(args, "ref", "credentials/set");
      requireField(args, "value", "credentials/set");
      return undefined;
    },
    "llm/listProviders": () => [{ provider: "deepseek-official", settingsNs: "llm-deepseek", active: true, name: "DeepSeek" }],
    "settings/describe": () => ({ writable: true, revision: 1, namespaces: [{ ns: "llm-deepseek", value: { apiKeyEnv: "DEEPSEEK_API_KEY" } }] }),
    // ---------------------------------------------------------------- workspace
    "workspace/create": (args) => {
      const request = requireField(args, "request", "workspace/create");
      const workspace = {
        workspaceId: `ws-${randomUUID().slice(0, 8)}`,
        path: request.path,
        title: request.path.split("/").filter(Boolean).pop() ?? request.path,
        sessionIds: [],
        createdAt: new Date(host.now()).toISOString(),
        updatedAt: new Date(host.now()).toISOString(),
      };
      host.workspaces.push(workspace);
      host.broadcastWorkspace({ type: "upsert", workspace: { ...workspace } });
      return { workspace: { ...workspace } };
    },
    "workspace/rename": (args) => {
      const request = requireField(args, "request", "workspace/rename");
      const workspace = requireWorkspace(host, request.workspaceId);
      if (!String(request.title ?? "").trim()) throw new RemoteError("workspace/title-invalid", "title must not be empty", { workspaceId: workspace.workspaceId });
      workspace.title = request.title.trim();
      workspace.updatedAt = new Date(host.now()).toISOString();
      host.broadcastWorkspace({ type: "upsert", workspace: { ...workspace } });
      return { workspace: { ...workspace } };
    },
    "workspace/delete": (args) => {
      const request = requireField(args, "request", "workspace/delete");
      const workspace = requireWorkspace(host, request.workspaceId);
      host.workspaces.splice(host.workspaces.indexOf(workspace), 1);
      host.broadcastWorkspace({ type: "remove", workspaceId: workspace.workspaceId });
      return { deleted: true };
    },
    "workspace/insertBefore": (args) => {
      const request = requireField(args, "request", "workspace/insertBefore");
      const workspace = requireWorkspace(host, request.workspaceId);
      host.workspaces.splice(host.workspaces.indexOf(workspace), 1);
      const at = request.beforeWorkspaceId ? host.workspaces.findIndex((w) => w.workspaceId === request.beforeWorkspaceId) : -1;
      if (at < 0) host.workspaces.push(workspace);
      else host.workspaces.splice(at, 0, workspace);
      const workspaceIds = host.workspaces.map((w) => w.workspaceId);
      host.broadcastWorkspace({ type: "order", workspaceIds });
      return { workspaceIds };
    },
    "workspace/archiveSession": (args) => {
      const request = requireField(args, "request", "workspace/archiveSession");
      return host.archiveSession(request.sessionId);
    },
    "directoryPicker/list": (args) => {
      const path = args.path ?? host.home;
      const entries = ["demo", "projects", "Documents", ".hidden"].map((name) => ({ name, path: `${path}/${name}`, hidden: name.startsWith(".") }));
      const crumbs = path.split("/").filter(Boolean).map((name, index, all) => ({ name, path: `/${all.slice(0, index + 1).join("/")}`, hidden: false }));
      return { path, home: host.home, crumbs, entries, truncated: false };
    },
    "directoryPicker/createDirectory": (args) => ({ path: `${requireField(args, "path", "directoryPicker/createDirectory")}/${requireField(args, "name", "directoryPicker/createDirectory")}` }),
    // ---------------------------------------------------------------- goals
    "goals/get": (args) => {
      requireField(args, "agentId", "goals/get");
      return undefined;
    },
    "goals/edit": goalUnavailable,
    "goals/pause": goalUnavailable,
    "goals/resume": goalUnavailable,
    "goals/clear": goalUnavailable,
    // ---------------------------------------------------------------- events
    "$events/result": (args) => {
      const { clientId, eventId, outcome } = args;
      if (typeof clientId !== "string" || typeof eventId !== "string" || !outcome || typeof outcome !== "object") {
        throw new RemoteError("gateway/bad-request", "invalid Remote event result");
      }
      host.receiveWaterfallResult({ clientId, eventId, outcome });
      return undefined;
    },
  };

  return async function dispatch(endpoint, payload) {
    const handler = handlers[endpoint];
    if (!handler) throw new RemoteError("gateway/unknown-endpoint", `unknown Remote endpoint ${endpoint}`, { endpoint });
    const args = requireArgs(payload);
    return handler(args);
  };
}

function requireWorkspace(host, workspaceId) {
  const workspace = host.workspaces.find((w) => w.workspaceId === workspaceId);
  if (!workspace) throw new RemoteError("workspace/not-found", `Workspace "${workspaceId}" not found`, { workspaceId });
  return workspace;
}

function goalUnavailable(args) {
  requireField(args, "agentId", "goals/*");
  throw new RemoteError("goal/not-found", "no current goal", {});
}
