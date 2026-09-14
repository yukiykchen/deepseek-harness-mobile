// Scripted assistant turns. A scenario is a JSON fixture (see fixtures/scenarios)
// replayed onto a session journal as durable events plus the process-local
// `assistant-stream` frames the Web client also receives.
import { randomUUID } from "node:crypto";

const sleep = (ms, signal) =>
  new Promise((resolve) => {
    if (ms <= 0 || signal?.aborted) return resolve();
    const onAbort = () => {
      clearTimeout(timer);
      resolve();
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    signal?.addEventListener("abort", onAbort, { once: true });
  });

export function pickScenario(scenarios, promptText) {
  const text = promptText.toLowerCase();
  let fallback;
  for (const scenario of scenarios) {
    if (scenario.match.includes("default")) fallback = scenario;
    if (scenario.match.some((keyword) => keyword !== "default" && text.includes(keyword))) return scenario;
  }
  return fallback ?? scenarios[0];
}

export class TurnRunner {
  constructor(host, session, scenario, { promptText, imageRefs, speed = 1 }) {
    this.host = host;
    this.session = session;
    this.scenario = scenario;
    this.promptText = promptText;
    this.imageRefs = imageRefs;
    this.speed = speed;
    this.abort = new AbortController();
  }

  cancel() {
    this.abort.abort();
  }

  get signal() {
    return this.abort.signal;
  }

  async run() {
    const { host, session } = this;
    session.step = 0;
    let reason = { kind: "completed" };
    try {
      for (const step of this.scenario.steps) {
        if (this.signal.aborted) break;
        await this.runStep(step);
      }
    } catch (error) {
      if (error?.turnError) reason = { kind: "error", error: { message: error.message, code: error.code ?? "UNKNOWN" } };
      else throw error;
    }
    if (this.signal.aborted) reason = { kind: "aborted", reason: { kind: "user" } };
    if (session.step > 0) host.appendEvent(session, "step/end", { turn: session.turn, step: session.step });
    host.appendEvent(session, "turn/end", { turn: session.turn, reason });
    session.activeTurn = null;
    host.setRunning(session, false);
  }

  openStep() {
    const { host, session } = this;
    session.step += 1;
    host.appendEvent(session, "step/start", { turn: session.turn, step: session.step });
  }

  async runStep(step) {
    switch (step.kind) {
      case "text":
      case "reasoning":
        return this.streamAssistant(step);
      case "tool":
        return this.runTool(step);
      case "image":
        return this.echoImages(step);
      case "approval":
        return this.runApproval(step);
      case "question":
        return this.runQuestion(step);
      case "error": {
        const error = new Error(step.message ?? "mock failure");
        error.turnError = true;
        error.code = step.code ?? "MOCK_FAILURE";
        // Emit an abandoned attempt like a failed model call.
        await this.streamAssistant({ kind: "text", text: step.partial ?? "", abandon: true, chunk: 16, delayMs: 20 });
        throw error;
      }
      case "pause":
        return sleep((step.delayMs ?? 500) / this.speed, this.signal);
      default:
        throw new Error(`unknown scenario step ${JSON.stringify(step.kind)}`);
    }
  }

  /** One model call producing optional reasoning and text; committed as assistant/message. */
  async streamAssistant(step) {
    const { host, session } = this;
    this.openStep();
    const attemptId = randomUUID();
    session.attemptRevision += 1;
    const revision = session.attemptRevision;
    const attempt = {
      attemptId,
      startedAfterSeq: session.seq,
      turn: session.turn,
      step: session.step,
      nextIndex: 0,
      stream: [],
    };
    session.activeTurn = { attempt };
    const send = (frame) => host.broadcastFollow(session.id, { type: "assistant-stream", frame });
    const chunk = (value) => {
      const time = host.now();
      attempt.stream.push({ time, chunk: value });
      send({ type: "chunk", attemptId, revision, index: attempt.nextIndex, time, chunk: value });
      attempt.nextIndex += 1;
    };
    send({ type: "start", attemptId, revision, startedAfterSeq: attempt.startedAfterSeq, turn: session.turn, step: session.step });

    const blocks = [];
    let index = 0;
    const delay = (step.delayMs ?? 30) / this.speed;
    const size = step.chunk ?? 12;
    if (step.kind === "reasoning" || step.reasoning) {
      const reasoning = expand(step.kind === "reasoning" ? step.text : step.reasoning, this.promptText);
      chunk({ type: "block-start", index, blockType: "reasoning" });
      for (const piece of split(reasoning, size)) {
        if (this.signal.aborted) break;
        chunk({ type: "reasoning-delta", index, text: piece });
        await sleep(delay, this.signal);
      }
      chunk({ type: "block-end", index, block: { type: "reasoning", text: reasoning } });
      blocks.push({ type: "reasoning", text: reasoning });
      index += 1;
    }
    const text = step.kind === "text" ? expand(step.text, this.promptText) : "";
    if (text) {
      chunk({ type: "block-start", index, blockType: "text" });
      let streamed = "";
      for (const piece of split(text, size)) {
        if (this.signal.aborted) break;
        streamed += piece;
        chunk({ type: "text-delta", index, text: piece });
        await sleep(delay, this.signal);
      }
      chunk({ type: "block-end", index, block: { type: "text", text: streamed } });
      blocks.push({ type: "text", text: streamed });
    }
    if (step.abandon || this.signal.aborted) {
      chunk({ type: "finish", reason: this.signal.aborted ? { kind: "aborted" } : { kind: "error", failure: { message: "attempt abandoned", code: "MOCK" } } });
      const event = host.appendEvent(session, "assistant/attempt", { turn: session.turn, step: session.step, stream: attempt.stream });
      send({ type: "end", attemptId, revision, index: attempt.nextIndex, outcome: { kind: "committed", eventType: "assistant/attempt", seq: event.seq } });
      session.activeTurn = null;
      return;
    }
    chunk({ type: "finish", reason: { kind: "stop" } });
    const event = host.appendEvent(session, "assistant/message", {
      turn: session.turn,
      step: session.step,
      message: { id: randomUUID(), role: "assistant", content: blocks, source: { kind: "model", provider: "mock", model: "mock-chat" } },
      stream: attempt.stream,
      usage: { inputTokens: 128, outputTokens: text.length },
    });
    send({ type: "end", attemptId, revision, index: attempt.nextIndex, outcome: { kind: "committed", eventType: "assistant/message", seq: event.seq } });
    session.activeTurn = null;
  }

  async runTool(step) {
    const { host, session } = this;
    if (session.step === 0) this.openStep();
    const callId = `call_${randomUUID().slice(0, 8)}`;
    const args = typeof step.arguments === "string" ? step.arguments : JSON.stringify(step.arguments ?? {});
    host.appendEvent(session, "tool/call", { turn: session.turn, step: session.step, callId, name: step.name, arguments: args });
    await sleep((step.delayMs ?? 500) / this.speed, this.signal);
    const output = expand(step.result ?? "", this.promptText);
    const data = {
      turn: session.turn,
      step: session.step,
      message: {
        id: randomUUID(),
        role: "user",
        content: [{ type: "tool-result", toolCallId: callId, content: [{ type: "text", text: output }], isError: Boolean(step.isError) }],
        source: { kind: "tool", callId },
      },
    };
    if (step.isError) data.error = { name: "ToolError", code: step.errorCode ?? "TOOL_FAILED" };
    if (step.meta) data.meta = step.meta;
    host.appendEvent(session, "tool/result", data);
  }

  /** Assistant message carrying the images the user just sent (or the fixture sample). */
  async echoImages(step) {
    const { host, session } = this;
    this.openStep();
    let refs = this.imageRefs;
    if (!refs?.length) {
      const sample = host.admitImage({ mediaType: "image/png", data: host.fixtures.sampleImage, name: "sample.png" });
      refs = [sample];
    }
    for (const ref of refs) session.referencedAttachments.add(ref.attachmentId);
    host.appendEvent(session, "assistant/message", {
      turn: session.turn,
      step: session.step,
      message: {
        id: randomUUID(),
        role: "assistant",
        content: [
          { type: "text", text: step.text ?? `Here is the image you sent (${refs.length}).` },
          ...refs.map((attachment) => ({ type: "image", attachment })),
        ],
        source: { kind: "model", provider: "mock", model: "mock-chat" },
      },
      stream: [],
    });
    await sleep((step.delayMs ?? 200) / this.speed, this.signal);
  }

  async runApproval(step) {
    const { host, session } = this;
    if (session.step === 0) this.openStep();
    const callId = `call_${randomUUID().slice(0, 8)}`;
    const args = JSON.stringify(step.arguments ?? { command: step.command ?? "rm -rf build" });
    host.appendEvent(session, "tool/call", { turn: session.turn, step: session.step, callId, name: step.toolName ?? "bash", arguments: args });
    const request = { toolName: step.toolName ?? "bash", callId, reason: step.reason ?? "This command modifies files." };
    const outcome = await host.waterfall("approval/request", session.id, request, this.signal);
    const decision = outcome.kind === "result" ? outcome.value : "unavailable";
    const allowed = decision === "allowed-once";
    host.appendEvent(session, "tool/result", {
      turn: session.turn,
      step: session.step,
      message: {
        id: randomUUID(),
        role: "user",
        content: [{
          type: "tool-result",
          toolCallId: callId,
          content: [{ type: "text", text: allowed ? (step.result ?? "done") : `Tool call ${decision} by user.` }],
          isError: !allowed,
        }],
        source: { kind: "tool", callId },
      },
    });
    await this.streamAssistant({ kind: "text", text: allowed ? (step.afterAllowed ?? "Done — the command ran.") : (step.afterRejected ?? "Okay, I will not run that command."), chunk: 16, delayMs: 20 });
  }

  async runQuestion(step) {
    const { host, session } = this;
    if (session.step === 0) this.openStep();
    const callId = `call_${randomUUID().slice(0, 8)}`;
    const questions = step.questions;
    host.appendEvent(session, "tool/call", { turn: session.turn, step: session.step, callId, name: "ask_user_question", arguments: JSON.stringify({ questions }) });
    const outcome = await host.waterfall("user-questions/request", session.id, { questions }, this.signal);
    const answer = outcome.kind === "result" ? outcome.value : { answers: [] };
    host.appendEvent(session, "tool/result", {
      turn: session.turn,
      step: session.step,
      message: {
        id: randomUUID(),
        role: "user",
        content: [{ type: "tool-result", toolCallId: callId, content: [{ type: "text", text: JSON.stringify(answer) }], isError: false }],
        source: { kind: "tool", callId },
      },
    });
    const picked = (answer.answers ?? []).map((a) => `${a.id}: ${[...(a.selected ?? []), a.custom].filter(Boolean).join(", ") || "(skipped)"}`).join("\n");
    await this.streamAssistant({ kind: "text", text: `Thanks. You answered:\n\n${picked || "_no answers_"}`, chunk: 16, delayMs: 20 });
  }
}

function split(text, size) {
  const pieces = [];
  for (let i = 0; i < text.length; i += size) pieces.push(text.slice(i, i + size));
  return pieces;
}

function expand(template, promptText) {
  return template.replaceAll("{{prompt}}", promptText.trim() || "(empty)");
}
