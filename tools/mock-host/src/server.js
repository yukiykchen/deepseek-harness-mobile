#!/usr/bin/env node
// Mock DeepSeek Harness Host speaking the 0.1.5 wire protocol:
//   GET  /?token=<launch token>        -> browser-session cookie (303 + Set-Cookie)
//   POST /api/<namespace>/<method>     -> { type:"client-request", rpcId, method, payload:{args} }
//   WS   /api/remote.mux               -> logical streams: $events, session/control,
//                                         workspace/follow, session/follow
//   GET  /dsh-scan-remote/api/auth     -> { authority, token } (relay plugin shim)
import { createServer } from "node:http";
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { WebSocketServer } from "ws";
import { BrowserAuth, requestAuthority } from "./auth.js";
import { createHandlers } from "./handlers.js";
import { createFakeLoader, handleMobileAdmin } from "./mobile-admin.js";
import { MockHost, RemoteError } from "./state.js";

const here = dirname(fileURLToPath(import.meta.url));
const fixturesDir = join(here, "..", "fixtures");

export function loadFixtures(dir = fixturesDir) {
  const json = (name) => JSON.parse(readFileSync(join(dir, name), "utf8"));
  const scenarios = readdirSync(join(dir, "scenarios"))
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => json(join("scenarios", f)));
  return {
    imageLimits: json("image-limits.json"),
    modelCatalog: json("model-catalog.json"),
    pluginInventory: json("plugin-inventory.json"),
    skills: json("skills.json").skills,
    seedSessions: json("seed-sessions.json").sessions,
    sampleImage: readFileSync(join(dir, "sample.png.base64"), "utf8").trim(),
    scenarios,
  };
}

function parseArgs(argv) {
  const options = { host: "127.0.0.1", port: 3080, token: undefined, speed: 1, verbose: false, requireAuth: true };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const next = () => argv[++i];
    if (arg === "--host") options.host = next();
    else if (arg === "--port") options.port = Number(next());
    else if (arg === "--token") options.token = next();
    else if (arg === "--speed") options.speed = Number(next());
    else if (arg === "--no-auth") options.requireAuth = false;
    else if (arg === "--verbose" || arg === "-v") options.verbose = true;
    else if (arg === "--help" || arg === "-h") {
      console.log("dsh-mock-host [--host 127.0.0.1] [--port 3080] [--token TOKEN] [--speed 1] [--no-auth] [--verbose]");
      process.exit(0);
    }
  }
  return options;
}

export function createMockServer(options = {}) {
  const fixtures = options.fixtures ?? loadFixtures();
  const host = new MockHost({ fixtures });
  const auth = new BrowserAuth({ launchToken: options.token });
  const runningTurns = new Map();
  // Stands in for the Cordis loader behind the companion admin plugin's route. The
  // official pluginInventory/list projects out of the same loader, exactly as a real
  // Host does, so a lifecycle change made through the plugin shows up in both.
  const mobileAdminLoader = createFakeLoader(host.fixtures.pluginInventory);
  const dispatch = createHandlers(host, {
    runningTurns,
    speed: options.speed ?? 1,
    pluginLoader: mobileAdminLoader,
  });
  const log = options.verbose ? (...a) => console.log("[mock-host]", ...a) : () => {};
  const requireAuth = options.requireAuth !== false;

  const authorized = (req) => !requireAuth || auth.isAuthenticated(req.headers);

  const server = createServer(async (req, res) => {
    const url = new URL(req.url ?? "/", "http://mock.internal");
    try {
      if (url.pathname === "/" && (req.method === "GET" || req.method === "HEAD")) {
        auth.authorizeIndex(req, res);
        return;
      }
      if (url.pathname === "/dsh-scan-remote/api/auth" && req.method === "GET") {
        const authority = requestAuthority(req.headers) ?? `127.0.0.1:${options.port ?? 3080}`;
        return json(res, 200, { authority, token: auth.launchToken });
      }
      if (url.pathname === "/dsh-scan-remote/api/state" && req.method === "GET") {
        return json(res, 200, { phase: "paired", hostId: "mock-host", hostName: "Mock Host", relay: "mock", dsh: "online", relayConnection: "connected", pairing: null, dshVersion: "0.1.5-rc.1", dshCompatible: true, compatibilityMessage: null, localActionsAllowed: true });
      }
      if (url.pathname.startsWith("/dsh-mobile/")) {
        return handleMobileAdmin(req, res, {
          loader: mobileAdminLoader,
          pathname: url.pathname,
          authorized: authorized(req),
          json,
          readJson,
        });
      }
      if (!url.pathname.startsWith("/api/")) {
        res.writeHead(404, { "content-type": "text/plain" });
        res.end("not found");
        return;
      }
      if (!authorized(req)) {
        res.writeHead(401, { "content-type": "text/plain; charset=utf-8" });
        res.end("unauthorized");
        return;
      }
      if (url.pathname === "/api/session.export" && req.method === "GET") {
        const session = host.requireSession(url.searchParams.get("sessionId") ?? "");
        res.writeHead(200, { "content-type": "application/json; charset=utf-8", "content-disposition": `attachment; filename="${session.id}.json"` });
        res.end(JSON.stringify(session.journal, null, 2));
        return;
      }
      if (req.method !== "POST") {
        res.writeHead(405, { "content-type": "text/plain" });
        res.end("method not allowed");
        return;
      }
      const endpoint = url.pathname.slice("/api/".length);
      const body = await readJson(req);
      if (body?.type !== "client-request" || typeof body.rpcId !== "string" || body.method !== endpoint) {
        res.writeHead(400, { "content-type": "text/plain" });
        res.end("invalid client-request envelope");
        return;
      }
      log("rpc", endpoint, JSON.stringify(body.payload).slice(0, 200));
      let result;
      try {
        const value = await dispatch(endpoint, body.payload);
        result = value === undefined ? { ok: true } : { ok: true, value };
      } catch (error) {
        result = { ok: false, error: toWireError(error) };
        log("rpc-error", endpoint, result.error.code, result.error.message);
      }
      json(res, 200, { type: "server-response", rpcId: body.rpcId, result });
    } catch (error) {
      console.error("[mock-host] request failed", error);
      if (!res.headersSent) {
        res.writeHead(500, { "content-type": "text/plain" });
        res.end("internal error");
      }
    }
  });

  const wss = new WebSocketServer({ noServer: true });
  server.on("upgrade", (req, socket, head) => {
    const url = new URL(req.url ?? "/", "http://mock.internal");
    if (url.pathname !== "/api/remote.mux") {
      rejectUpgrade(socket, 404, "Not Found");
      return;
    }
    if (!authorized(req)) {
      rejectUpgrade(socket, 401, "Unauthorized");
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => serveMux(ws, { host, log }));
  });

  return {
    server,
    host,
    auth,
    runningTurns,
    listen(port, hostname) {
      return new Promise((resolve) => server.listen(port, hostname, () => resolve(server.address())));
    },
    close() {
      for (const runner of runningTurns.values()) runner.cancel();
      for (const client of wss.clients) client.terminate();
      return new Promise((resolve) => server.close(() => resolve()));
    },
  };
}

function serveMux(ws, { host, log }) {
  const streams = new Map(); // streamId -> dispose()
  const send = (message) => {
    if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(message));
  };
  const item = (streamId) => (value) => send({ type: "item", streamId, value });
  ws.on("message", (raw, isBinary) => {
    if (isBinary) return ws.close(1003, "text messages required");
    let message;
    try {
      message = JSON.parse(raw.toString("utf8"));
    } catch {
      return ws.close(1008, "invalid Remote stream request");
    }
    if (message.type === "cancel") {
      streams.get(message.streamId)?.();
      streams.delete(message.streamId);
      return;
    }
    if (message.type !== "open" || typeof message.streamId !== "string" || streams.has(message.streamId)) {
      return ws.close(1008, "invalid Remote stream request");
    }
    log("stream open", message.endpoint, message.streamId);
    try {
      const dispose = openStream(host, message.endpoint, message.payload, item(message.streamId), () => send({ type: "end", streamId: message.streamId }));
      streams.set(message.streamId, dispose);
    } catch (error) {
      send({ type: "error", streamId: message.streamId, error: toWireError(error) });
    }
  });
  ws.on("close", () => {
    for (const dispose of streams.values()) dispose();
    streams.clear();
  });
  const ping = setInterval(() => {
    if (ws.readyState === ws.OPEN) ws.ping();
  }, 2000);
  ping.unref();
  ws.on("close", () => clearInterval(ping));
}

function openStream(host, endpoint, payload, emit, end) {
  const args = payload?.args;
  if (!args || typeof args !== "object") {
    throw new RemoteError("gateway/arguments-invalid", "Remote payload must contain exactly one plain-object args field");
  }
  switch (endpoint) {
    case "$events": {
      const subscription = host.subscribeEvents(emit);
      return () => subscription.dispose();
    }
    case "session/control": {
      emit({ type: "baseline", value: host.controlBaseline() });
      return host.subscribeControl(emit);
    }
    case "workspace/follow": {
      emit({ type: "baseline", value: host.workspaceBaseline() });
      return host.subscribeWorkspace(emit);
    }
    case "session/follow": {
      const request = args.request;
      if (!request?.address?.sessionId) throw new RemoteError("gateway/arguments-invalid", "session/follow requires request.address.sessionId");
      const session = host.requireSession(request.address.sessionId);
      const snapshot = host.snapshot(session, request.maxMessages ?? 80);
      if (!request.assistantStream) delete snapshot.assistantStream;
      emit(snapshot);
      return host.subscribeFollow(session.id, (frame) => {
        if (frame.type === "assistant-stream" && !request.assistantStream) return;
        emit(frame);
      });
    }
    default:
      void end;
      throw new RemoteError("gateway/unknown-endpoint", `unknown Remote stream ${endpoint}`, { endpoint });
  }
}

function toWireError(error) {
  if (error instanceof RemoteError) return { code: error.code, message: error.message, details: error.details ?? {} };
  return { code: "gateway/internal", message: error instanceof Error ? error.message : String(error), details: {} };
}

function json(res, status, body) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  res.end(JSON.stringify(body));
}

function rejectUpgrade(socket, status, reason) {
  socket.end(`HTTP/1.1 ${status} ${reason}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => {
      try {
        resolve(chunks.length === 0 ? undefined : JSON.parse(Buffer.concat(chunks).toString("utf8")));
      } catch (error) {
        reject(error);
      }
    });
    req.on("error", reject);
  });
}

const isMain = process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1];
if (isMain) {
  const options = parseArgs(process.argv.slice(2));
  const mock = createMockServer(options);
  mock.listen(options.port, options.host).then((address) => {
    const base = `http://${options.host}:${address.port}`;
    console.log(`dsh mock host: ${mock.auth.authenticatedUrl(base)}`);
    console.log(`launch token: ${mock.auth.launchToken}`);
    console.log(`relay auth shim: ${base}/dsh-scan-remote/api/auth`);
    console.log("scenario keywords: " + mock.host.fixtures.scenarios.map((s) => s.match.join("|")).join(", "));
  });
}
