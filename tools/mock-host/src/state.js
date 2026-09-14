// In-memory Host state: sessions with durable journals, workspaces, control
// snapshots (queues / jobs / projections), image attachments, and the
// subscriber sets behind the Remote streams.
import { createHash, randomUUID } from "node:crypto";

export class RemoteError extends Error {
  constructor(code, message, details = {}) {
    super(message);
    this.code = code;
    this.details = details;
  }
}

export class MockHost {
  constructor({ fixtures, home = "/Users/mock", now = () => Date.now() }) {
    this.fixtures = fixtures;
    this.home = home;
    this.now = now;
    this.sessions = new Map();
    this.workspaces = [];
    this.archivedSessionIds = new Set();
    this.attachments = new Map();
    this.followers = new Map(); // sessionId -> Set<send>
    this.controlClients = new Set();
    this.workspaceClients = new Set();
    this.eventClients = new Map(); // clientId -> send
    this.pendingWaterfalls = new Map(); // eventId -> { clientId, resolve, reject }
    this.seedWorkspace();
  }

  // ---------------------------------------------------------------- sessions

  seedWorkspace() {
    const workspaceId = "ws-demo";
    this.workspaces.push({
      workspaceId,
      path: `${this.home}/demo`,
      title: "demo",
      sessionIds: [],
      createdAt: new Date(this.now()).toISOString(),
      updatedAt: new Date(this.now()).toISOString(),
    });
    for (const seed of this.fixtures.seedSessions ?? []) {
      const session = this.createSession({ workspaceId, sessionId: seed.sessionId, emit: false });
      this.replaySeed(session, seed);
    }
  }

  replaySeed(session, seed) {
    for (const turn of seed.turns ?? []) {
      this.appendEvent(session, "turn/start", { turn: session.turn + 1 });
      session.turn += 1;
      this.appendEvent(session, "user/message", this.userMessage([{ type: "text", text: turn.user }], "seed"));
      session.step = 1;
      this.appendEvent(session, "step/start", { turn: session.turn, step: session.step });
      this.appendEvent(session, "assistant/message", {
        turn: session.turn,
        step: session.step,
        message: {
          id: randomUUID(),
          role: "assistant",
          content: [{ type: "text", text: turn.assistant }],
          source: { kind: "model", provider: "mock", model: "mock-chat" },
        },
        stream: [],
      });
      this.appendEvent(session, "step/end", { turn: session.turn, step: session.step });
      this.appendEvent(session, "turn/end", { turn: session.turn, reason: { kind: "completed" } });
      session.lastPromptAt = this.now();
      session.blank = false;
    }
    if (seed.title) this.setProjection(session, "title", seed.title);
    if (seed.archived) this.archivedSessionIds.add(session.id);
  }

  createSession({ workspaceId, cwd, sessionId, emit = true } = {}) {
    const id = sessionId ?? randomUUID();
    if (this.sessions.has(id)) return this.sessions.get(id);
    const workspace = this.workspaces.find((w) => w.workspaceId === workspaceId) ?? this.workspaces[0];
    const session = {
      id,
      createdAt: this.now(),
      updatedAt: this.now(),
      cwd: cwd ?? workspace?.path ?? this.home,
      running: false,
      blank: true,
      lastPromptAt: null,
      seq: -1,
      turn: 0,
      step: 0,
      journal: [],
      projections: new Map(),
      queue: [],
      jobs: [],
      attemptRevision: 0,
      activeTurn: null,
      referencedAttachments: new Set(),
    };
    this.sessions.set(id, session);
    this.setProjection(session, "imageLimits", this.fixtures.imageLimits, { broadcast: false });
    if (workspace && !workspace.sessionIds.includes(id)) {
      workspace.sessionIds.push(id);
      workspace.updatedAt = new Date(this.now()).toISOString();
      this.broadcastWorkspace({ type: "upsert", workspace: { ...workspace } });
    }
    if (emit) this.emitEvent("api-session/added", [this.summary(session)]);
    return session;
  }

  requireSession(sessionId) {
    const session = this.sessions.get(sessionId);
    if (!session) {
      throw new RemoteError("session/not-found", `session "${sessionId}" not found`, { sessionId });
    }
    return session;
  }

  summary(session) {
    const values = {
      sessionListMetadata: { blank: session.blank, lastPromptAt: session.lastPromptAt },
    };
    const title = session.projections.get("title");
    if (title) values.title = title.value;
    return {
      sessionId: session.id,
      updatedAt: session.updatedAt,
      running: session.running,
      blank: session.blank,
      cwd: session.cwd,
      projections: { asOfSeq: session.seq, values },
    };
  }

  listSummaries() {
    return [...this.sessions.values()]
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .map((s) => this.summary(s));
  }

  header(session) {
    return {
      version: 3,
      id: session.id,
      createdAt: session.createdAt,
      cwd: session.cwd,
      isSeeded: false,
    };
  }

  // ---------------------------------------------------------------- journal

  appendEvent(session, type, data) {
    session.seq += 1;
    const event = { type, seq: session.seq, time: this.now(), data };
    session.journal.push({ type: "event", event });
    this.broadcastFollow(session.id, { type: "event", event });
    return event;
  }

  userMessage(content, rpcId, clientTimeZone) {
    const source = { kind: "user" };
    if (rpcId) source.rpcId = rpcId;
    if (clientTimeZone) source.clientTimeZone = clientTimeZone;
    return { id: randomUUID(), role: "user", content, source };
  }

  /** Message-aligned page: `maxMessages` user/assistant messages counted from the tail. */
  page(session, { beforeSeq, maxMessages = 80 }) {
    const upper = beforeSeq === undefined ? session.journal.length : session.journal.findIndex((r) => r.event.seq >= beforeSeq);
    const end = upper < 0 ? session.journal.length : upper;
    let start = end;
    let messages = 0;
    while (start > 0) {
      const type = session.journal[start - 1].event.type;
      if (type === "user/message" || type === "assistant/message") {
        if (messages >= maxMessages) break;
        messages += 1;
      }
      start -= 1;
    }
    // Snap to the turn boundary that opens the first included message.
    while (start > 0 && session.journal[start].event.type !== "turn/start" && session.journal[start].event.type !== "user/message") {
      start -= 1;
    }
    return { records: session.journal.slice(start, end), hasMore: start > 0 };
  }

  snapshot(session, maxMessages) {
    const { records, hasMore } = this.page(session, { maxMessages });
    const frame = {
      type: "snapshot",
      header: this.header(session),
      cursor: session.seq,
      records,
      hasMore,
      projections: this.projectionBaseline(session),
    };
    frame.assistantStream = session.activeTurn?.attempt
      ? { revision: session.attemptRevision, activeAttempt: session.activeTurn.attempt }
      : { revision: session.attemptRevision };
    return frame;
  }

  // ---------------------------------------------------------------- projections / control

  projectionBaseline(session) {
    const values = {};
    for (const [key, cell] of session.projections) values[key] = cell.value;
    values.sessionListMetadata = { blank: session.blank, lastPromptAt: session.lastPromptAt };
    return { asOfSeq: session.seq, values };
  }

  setProjection(session, key, value, { broadcast = true } = {}) {
    session.projections.set(key, { value, seq: session.seq });
    if (broadcast) {
      this.broadcastControl({ type: "projection", sessionId: session.id, key, value, seq: session.seq });
    }
  }

  controlBaseline() {
    const queues = {};
    const jobs = {};
    const projections = {};
    for (const session of this.sessions.values()) {
      queues[session.id] = session.queue;
      jobs[session.id] = session.jobs;
      projections[session.id] = this.projectionBaseline(session);
    }
    return { queues, jobs, projections };
  }

  setRunning(session, running) {
    if (session.running === running) return;
    session.running = running;
    this.emitEvent("api-session/status", [session.id, running]);
  }

  touch(session) {
    session.updatedAt = this.now();
    session.lastPromptAt = session.updatedAt;
    session.blank = false;
    this.emitEvent("api-session/activity", [session.id, session.updatedAt]);
  }

  // ---------------------------------------------------------------- workspaces

  workspaceBaseline() {
    return { items: this.workspaces.map((w) => ({ ...w })), archivedSessionIds: [...this.archivedSessionIds] };
  }

  archiveSession(sessionId) {
    this.requireSession(sessionId);
    this.archivedSessionIds.add(sessionId);
    this.broadcastWorkspace({ type: "archived", archivedSessionIds: [...this.archivedSessionIds] });
    return { archivedSessionIds: [...this.archivedSessionIds] };
  }

  // ---------------------------------------------------------------- attachments

  admitImage(part) {
    const limits = this.fixtures.imageLimits;
    if (!limits.mediaTypes.includes(part.mediaType)) {
      throw new RemoteError("session/attachment-invalid", `Unsupported image type ${part.mediaType}.`, {
        reason: "UNSUPPORTED_IMAGE_TYPE",
      });
    }
    let bytes;
    try {
      bytes = Buffer.from(part.data, "base64");
      if (bytes.toString("base64").replace(/=+$/u, "") !== part.data.replace(/=+$/u, "")) throw new Error("non-canonical");
    } catch {
      throw new RemoteError("session/attachment-invalid", "Image data is not canonical base64.", {
        reason: "INVALID_IMAGE_BASE64",
      });
    }
    if (bytes.byteLength > limits.maxImageBytes) {
      throw new RemoteError("session/attachment-invalid", `Image exceeds ${limits.maxImageBytes} bytes.`, {
        reason: "IMAGE_TOO_LARGE",
      });
    }
    const dimensions = pngDimensions(bytes) ?? { width: 1, height: 1 };
    if (dimensions.width * dimensions.height > limits.maxImagePixels) {
      throw new RemoteError("session/attachment-invalid", "Image has too many pixels.", {
        reason: "IMAGE_TOO_MANY_PIXELS",
      });
    }
    const attachmentId = createHash("sha256").update(bytes).digest("hex");
    const ref = {
      attachmentId,
      mediaType: part.mediaType,
      bytes: bytes.byteLength,
      width: dimensions.width,
      height: dimensions.height,
    };
    if (part.name) ref.name = part.name.split(/[\\/]/u).pop();
    this.attachments.set(attachmentId, { ref, data: part.data });
    return ref;
  }

  admitPromptContent(content) {
    const limits = this.fixtures.imageLimits;
    const images = content.filter((p) => p.type === "image");
    if (images.length > limits.maxImagesPerMessage) {
      throw new RemoteError("session/attachment-invalid", `At most ${limits.maxImagesPerMessage} images per message.`, {
        reason: "TOO_MANY_IMAGES",
      });
    }
    const totalBytes = images.reduce((sum, p) => sum + Math.floor((p.data?.length ?? 0) * 3 / 4), 0);
    if (totalBytes > limits.maxMessageImageBytes) {
      throw new RemoteError("session/attachment-invalid", "Images in this message exceed the total byte limit.", {
        reason: "IMAGES_TOO_LARGE",
      });
    }
    return content.map((part) => (part.type === "image" ? { type: "image", attachment: this.admitImage(part) } : part));
  }

  readAttachment(session, attachmentId) {
    if (!session.referencedAttachments.has(attachmentId)) {
      throw new RemoteError("session/attachment-invalid", "Image is not referenced by this session.", {
        reason: "ATTACHMENT_NOT_REFERENCED",
      });
    }
    const stored = this.attachments.get(attachmentId);
    if (!stored) {
      throw new RemoteError("session/attachment-invalid", "Image bytes are missing.", { reason: "ATTACHMENT_NOT_FOUND" });
    }
    return { attachment: stored.ref, data: stored.data };
  }

  // ---------------------------------------------------------------- streams

  subscribeFollow(sessionId, send) {
    let set = this.followers.get(sessionId);
    if (!set) this.followers.set(sessionId, (set = new Set()));
    set.add(send);
    return () => set.delete(send);
  }

  broadcastFollow(sessionId, frame) {
    for (const send of this.followers.get(sessionId) ?? []) send(frame);
  }

  subscribeControl(send) {
    this.controlClients.add(send);
    return () => this.controlClients.delete(send);
  }

  broadcastControl(frame) {
    for (const send of this.controlClients) send(frame);
  }

  subscribeWorkspace(send) {
    this.workspaceClients.add(send);
    return () => this.workspaceClients.delete(send);
  }

  broadcastWorkspace(frame) {
    for (const send of this.workspaceClients) send(frame);
  }

  subscribeEvents(send) {
    const clientId = randomUUID();
    this.eventClients.set(clientId, send);
    send({ type: "ready", clientId, host: { home: this.home } });
    return {
      clientId,
      dispose: () => {
        this.eventClients.delete(clientId);
        for (const [eventId, pending] of this.pendingWaterfalls) {
          if (pending.clientId === clientId) {
            this.pendingWaterfalls.delete(eventId);
            pending.reject(new Error("client generation ended"));
          }
        }
      },
    };
  }

  emitEvent(event, args) {
    const frame = { type: "emit", event, args };
    for (const send of this.eventClients.values()) send(frame);
  }

  /**
   * Deliver one Agent-scoped waterfall to every connected client. The first
   * client that answers with `result` settles it; `next` defers to the Host
   * default. Resolves with `{ kind, value }`.
   */
  waterfall(event, agentId, request, signal) {
    return new Promise((resolve, reject) => {
      const eventId = randomUUID();
      const clients = [...this.eventClients.entries()];
      if (clients.length === 0) {
        resolve({ kind: "next" });
        return;
      }
      let remaining = clients.length;
      const settle = (outcome) => {
        if (!this.pendingWaterfalls.has(eventId)) return;
        this.pendingWaterfalls.delete(eventId);
        for (const [, send] of clients) send({ type: "cancel", eventId });
        resolve(outcome);
      };
      this.pendingWaterfalls.set(eventId, {
        clientId: null,
        resolve: (clientId, outcome) => {
          if (outcome.kind === "next") {
            remaining -= 1;
            if (remaining <= 0) settle({ kind: "next" });
            return;
          }
          settle(outcome);
        },
        reject,
      });
      signal?.addEventListener("abort", () => settle({ kind: "rejected", error: { name: "AbortError", message: "cancelled" } }), { once: true });
      for (const [, send] of clients) {
        send({ type: "waterfall", event, eventId, agentId, request });
      }
    });
  }

  receiveWaterfallResult({ clientId, eventId, outcome }) {
    if (!this.eventClients.has(clientId)) {
      throw new RemoteError("gateway/bad-request", "Remote event result identifies no active event stream");
    }
    const pending = this.pendingWaterfalls.get(eventId);
    if (!pending) return; // already settled or cancelled
    pending.resolve(clientId, outcome);
  }
}

function pngDimensions(bytes) {
  if (bytes.byteLength < 24) return undefined;
  const signature = bytes.subarray(0, 8).toString("hex");
  if (signature !== "89504e470d0a1a0a") return undefined;
  return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
}
