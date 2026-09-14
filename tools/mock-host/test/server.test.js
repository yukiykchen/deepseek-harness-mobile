import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import WebSocket from "ws";
import { createMockServer } from "../src/server.js";

async function start() {
  const mock = createMockServer({ token: "test-token", speed: 50 });
  const address = await mock.listen(0, "127.0.0.1");
  const base = `http://127.0.0.1:${address.port}`;
  return { mock, base, port: address.port };
}

async function mintCookie(base) {
  const response = await fetch(`${base}/?token=test-token`, { redirect: "manual" });
  assert.equal(response.status, 303);
  const setCookie = response.headers.get("set-cookie");
  assert.ok(setCookie);
  return setCookie.split(";")[0];
}

function rpc(base, cookie) {
  return async (endpoint, args = {}) => {
    const rpcId = randomUUID();
    const response = await fetch(`${base}/api/${endpoint}`, {
      method: "POST",
      headers: { "content-type": "application/json", cookie },
      body: JSON.stringify({ type: "client-request", rpcId, method: endpoint, payload: { args } }),
    });
    assert.equal(response.status, 200, `${endpoint} HTTP ${response.status}`);
    const body = await response.json();
    assert.equal(body.type, "server-response");
    assert.equal(body.rpcId, rpcId);
    return body.result;
  };
}

class Mux {
  constructor(base, cookie) {
    this.ws = new WebSocket(`${base.replace("http", "ws")}/api/remote.mux`, { headers: { cookie } });
    this.queues = new Map();
    this.ws.on("message", (raw) => {
      const message = JSON.parse(raw.toString());
      const queue = this.queues.get(message.streamId);
      queue?.push(message);
    });
    this.ready = new Promise((resolve, reject) => {
      this.ws.once("open", resolve);
      this.ws.once("error", reject);
    });
  }

  open(endpoint, args = {}) {
    const streamId = randomUUID();
    const queue = [];
    const waiters = [];
    const push = (message) => {
      const waiter = waiters.shift();
      if (waiter) waiter(message);
      else queue.push(message);
    };
    this.queues.set(streamId, { push });
    this.ws.send(JSON.stringify({ type: "open", streamId, endpoint, payload: { args } }));
    return {
      streamId,
      next: (timeoutMs = 5000) =>
        new Promise((resolve, reject) => {
          if (queue.length) return resolve(queue.shift());
          const timer = setTimeout(() => reject(new Error(`timeout waiting on ${endpoint}`)), timeoutMs);
          waiters.push((message) => {
            clearTimeout(timer);
            resolve(message);
          });
        }),
      cancel: () => this.ws.send(JSON.stringify({ type: "cancel", streamId })),
    };
  }

  close() {
    this.ws.close();
  }
}

test("rejects unauthenticated /api and mints a cookie from the launch token", async () => {
  const { mock, base } = await start();
  try {
    const denied = await fetch(`${base}/api/session/list`, { method: "POST", body: "{}" });
    assert.equal(denied.status, 401);
    const cookie = await mintCookie(base);
    assert.match(cookie, /^dsh-auth-[A-Za-z0-9_-]+=v1\./u);
    const auth = await (await fetch(`${base}/dsh-scan-remote/api/auth`)).json();
    assert.equal(auth.token, "test-token");
    const call = rpc(base, cookie);
    const list = await call("session/list", { _request: {} });
    assert.equal(list.ok, true);
    assert.ok(list.value.items.length >= 2);
    const wrong = await call("session/list", { request: {} });
    assert.equal(wrong.ok, false);
    assert.equal(wrong.error.code, "gateway/arguments-invalid");
  } finally {
    await mock.close();
  }
});

test("streams a scripted turn through session/follow with assistant-stream frames", async () => {
  const { mock, base } = await start();
  const cookie = await mintCookie(base);
  const call = rpc(base, cookie);
  const mux = new Mux(base, cookie);
  try {
    await mux.ready;
    const events = mux.open("$events");
    const ready = await events.next();
    assert.equal(ready.value.type, "ready");
    const control = mux.open("session/control");
    const baseline = await control.next();
    assert.equal(baseline.value.type, "baseline");
    const workspace = mux.open("workspace/follow");
    const wsBaseline = await workspace.next();
    assert.equal(wsBaseline.value.type, "baseline");
    assert.deepEqual(wsBaseline.value.value.archivedSessionIds, ["seed-archived-notes"]);

    const created = await call("session/create", { request: { workspaceId: "ws-demo" } });
    assert.equal(created.ok, true);
    const sessionId = created.value.sessionId;
    const added = await events.next();
    assert.equal(added.value.type, "emit");
    assert.equal(added.value.event, "api-session/added");
    assert.equal(added.value.args[0].sessionId, sessionId);
    const upsert = await workspace.next();
    assert.equal(upsert.value.type, "upsert");
    assert.ok(upsert.value.workspace.sessionIds.includes(sessionId));

    const follow = mux.open("session/follow", { request: { address: { kind: "session", sessionId }, maxMessages: 80, assistantStream: true } });
    const snapshot = await follow.next();
    assert.equal(snapshot.value.type, "snapshot");
    assert.equal(snapshot.value.header.id, sessionId);
    assert.equal(snapshot.value.records.length, 0);
    assert.ok(snapshot.value.projections.values.imageLimits);

    const requestId = randomUUID();
    const prompt = await call("session/prompt", {
      request: { requestId, sessionId, mode: "queue", content: [{ type: "text", text: "hello tool world" }], clientTimeZone: "UTC" },
    });
    assert.equal(prompt.ok, true);

    const seen = [];
    let turnEnded = false;
    while (!turnEnded) {
      const frame = (await follow.next(10000)).value;
      seen.push(frame);
      if (frame.type === "event" && frame.event.type === "turn/end") turnEnded = true;
    }
    const eventTypes = seen.filter((f) => f.type === "event").map((f) => f.event.type);
    assert.equal(eventTypes[0], "turn/start");
    assert.equal(eventTypes[1], "user/message");
    assert.ok(eventTypes.includes("tool/call"));
    assert.ok(eventTypes.includes("tool/result"));
    assert.ok(eventTypes.includes("assistant/message"));
    const userEvent = seen.find((f) => f.type === "event" && f.event.type === "user/message").event;
    assert.equal(userEvent.data.source.rpcId, requestId);
    const streamFrames = seen.filter((f) => f.type === "assistant-stream").map((f) => f.frame);
    assert.equal(streamFrames[0].type, "start");
    assert.ok(streamFrames.some((f) => f.type === "chunk" && f.chunk.type === "text-delta"));
    assert.ok(streamFrames.some((f) => f.type === "end" && f.outcome.kind === "committed"));
    const editResult = seen.find((f) => f.type === "event" && f.event.type === "tool/result" && f.event.data.meta);
    assert.ok(editResult.event.data.meta.diffs[0].path);
    const seqs = seen.filter((f) => f.type === "event").map((f) => f.event.seq);
    assert.deepEqual(seqs, seqs.map((_, i) => seqs[0] + i), "seq must be contiguous");

    // Rename + title-invalid, archive.
    const invalid = await call("session/rename", { request: { sessionId, title: "   " } });
    assert.equal(invalid.error.code, "session/title-invalid");
    const renamed = await call("session/rename", { request: { sessionId, title: "Tools demo" } });
    assert.equal(renamed.value.title, "Tools demo");
    const projection = await control.next();
    assert.equal(projection.value.type, "projection");
    const archived = await call("workspace/archiveSession", { request: { sessionId } });
    assert.ok(archived.value.archivedSessionIds.includes(sessionId));
    const archivedFrame = await workspace.next();
    assert.equal(archivedFrame.value.type, "archived");
  } finally {
    mux.close();
    await mock.close();
  }
});

test("admits images against imageLimits and serves them back through session/attachment", async () => {
  const { mock, base } = await start();
  const cookie = await mintCookie(base);
  const call = rpc(base, cookie);
  const mux = new Mux(base, cookie);
  try {
    await mux.ready;
    const created = await call("session/create", { request: {} });
    const sessionId = created.value.sessionId;
    const follow = mux.open("session/follow", { request: { address: { kind: "session", sessionId }, assistantStream: true } });
    await follow.next();

    const bad = await call("session/prompt", {
      request: { requestId: randomUUID(), sessionId, mode: "queue", content: [{ type: "image", mediaType: "image/bmp", data: "AAAA" }] },
    });
    assert.equal(bad.error.code, "session/attachment-invalid");
    assert.equal(bad.error.details.reason, "UNSUPPORTED_IMAGE_TYPE");

    const png = mock.host.fixtures.sampleImage;
    const ok = await call("session/prompt", {
      request: {
        requestId: randomUUID(), sessionId, mode: "queue",
        content: [{ type: "text", text: "what is in this image?" }, { type: "image", mediaType: "image/png", data: png, name: "/tmp/photo.png" }],
      },
    });
    assert.equal(ok.ok, true);
    let userEvent;
    let ended = false;
    while (!ended) {
      const frame = (await follow.next(10000)).value;
      if (frame.type === "event" && frame.event.type === "user/message") userEvent = frame.event;
      if (frame.type === "event" && frame.event.type === "turn/end") ended = true;
    }
    const imageBlock = userEvent.data.content.find((b) => b.type === "image");
    assert.equal(imageBlock.attachment.name, "photo.png");
    assert.equal(imageBlock.attachment.width, 64);
    assert.equal(imageBlock.attachment.mediaType, "image/png");
    assert.equal(imageBlock.data, undefined, "no raw base64 in the log");
    const read = await call("session/attachment", { request: { sessionId, attachmentId: imageBlock.attachment.attachmentId } });
    assert.equal(read.value.data, png);
    const other = await call("session/create", { request: {} });
    const denied = await call("session/attachment", { request: { sessionId: other.value.sessionId, attachmentId: imageBlock.attachment.attachmentId } });
    assert.equal(denied.error.details.reason, "ATTACHMENT_NOT_REFERENCED");
  } finally {
    mux.close();
    await mock.close();
  }
});

test("delivers approval waterfalls and settles them via $events/result", async () => {
  const { mock, base } = await start();
  const cookie = await mintCookie(base);
  const call = rpc(base, cookie);
  const mux = new Mux(base, cookie);
  try {
    await mux.ready;
    const events = mux.open("$events");
    const ready = (await events.next()).value;
    const sessionId = (await call("session/create", { request: {} })).value.sessionId;
    await events.next(); // api-session/added
    const follow = mux.open("session/follow", { request: { address: { kind: "session", sessionId }, assistantStream: true } });
    await follow.next();
    await call("session/prompt", { request: { requestId: randomUUID(), sessionId, mode: "queue", content: [{ type: "text", text: "please approve this" }] } });
    let frame;
    do frame = (await events.next(10000)).value; while (frame.type !== "waterfall");
    assert.equal(frame.event, "approval/request");
    assert.equal(frame.agentId, sessionId);
    assert.equal(frame.request.toolName, "bash");
    const settled = await call("$events/result", { clientId: ready.clientId, eventId: frame.eventId, outcome: { kind: "result", value: "allowed-once" } });
    assert.equal(settled.ok, true);
    let toolResult;
    let ended = false;
    while (!ended) {
      const f = (await follow.next(10000)).value;
      if (f.type === "event" && f.event.type === "tool/result") toolResult = f.event;
      if (f.type === "event" && f.event.type === "turn/end") ended = true;
    }
    assert.equal(toolResult.data.message.content[0].isError, false);
    const cancel = (await events.next()).value;
    assert.equal(cancel.type, "cancel");
  } finally {
    mux.close();
    await mock.close();
  }
});

test("cancel ends the turn with an aborted reason", async () => {
  const { mock, base } = await start();
  const cookie = await mintCookie(base);
  const call = rpc(base, cookie);
  const mux = new Mux(base, cookie);
  try {
    await mux.ready;
    const sessionId = (await call("session/create", { request: {} })).value.sessionId;
    const follow = mux.open("session/follow", { request: { address: { kind: "session", sessionId }, assistantStream: true } });
    await follow.next();
    await call("session/prompt", { request: { requestId: randomUUID(), sessionId, mode: "queue", content: [{ type: "text", text: "long stress" }] } });
    await follow.next(); // turn/start
    await call("session/cancel", { request: { sessionId } });
    let end;
    do end = (await follow.next(10000)).value; while (!(end.type === "event" && end.event.type === "turn/end"));
    assert.equal(end.event.data.reason.kind, "aborted");
  } finally {
    mux.close();
    await mock.close();
  }
});
