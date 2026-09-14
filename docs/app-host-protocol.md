# App ↔ Host protocol (DSH 0.1.5)

This document records the Host protocol the DSH App actually speaks. The authoritative implementation is
`shared/src/commonMain/kotlin/com/example/dsh/dsh/DshHostRuntime.kt` (transport) and
`DshRemoteHostRepository.kt` (reducers). Endpoint names and argument names are taken verbatim from the
generated Typert descriptors shipped in `@deepseek-ai/dsh-*@0.1.5-rc.1`; the App defines no Host methods of its own.

The pre-0.1.2 "apiproxy" protocol (`/api/events.mux`, `/api/events.host`, `host.describe`, `session.prompt`,
`POST /api/respond`) is gone from the Host and from this App.

Scan-relay pairing and the sealed tunnel are not part of the Host protocol; see
[dsh-scan-remote](https://github.com/yukiykchen/dsh-scan-remote). After pairing the App only talks to the Host on
the phone's loopback gateway; the envelope is identical for SSH and direct connections.

## 1. Transports and authentication

| Mode | `baseUrl` | Gateway auth | Host auth | Launch-token source |
| --- | --- | --- | --- | --- |
| Scan (relay) | phone loopback gateway → tunnel → `127.0.0.1:3080` | `Authorization: Bearer <local token>` (stripped by the gateway) | browser-session cookie | `GET /dsh-scan-remote/api/auth` through the tunnel (patched plugin) |
| SSH | `http://127.0.0.1:<forwarded port>` | none | browser-session cookie | pasted from the `dsh web` banner, stored in the SSH profile |
| Direct (dev) | any `http(s)://host:port` the phone can open | none | browser-session cookie | plugin route if present (mock Host), else pasted |

### Browser-session cookie

DSH ≥ 0.1.2 rejects every `/api` request and the `/api/remote.mux` upgrade without a cookie, loopback included:

1. `GET {baseUrl}/?token={launch token}` — must be sent with redirects **disabled** (native `mintAuthCookie`
   bridge); the Host answers `303 Location: /` with `Set-Cookie: dsh-auth-<sha256(authority)>=v1.<body>.<sig>; Max-Age=30d; HttpOnly`.
2. The `name=value` pair is sent as the `Cookie` header on every RPC (Kuikly `NetworkModule.httpRequest(cookie=)`) and on
   the WebSocket upgrade (`DshWebSocketModule.connect(cookie=)`).
3. The cookie is bound to the `Host` authority the Host sees (`127.0.0.1:3080` behind the relay plugin, the forwarded
   port for SSH). It is persisted per connection scope in `dsh_settings` (`auth_cookie:<scope>`).
4. A `401` on any RPC or upgrade clears the cookie and re-mints once from the known token; if that fails the runtime
   publishes `AUTH_REQUIRED` and the UI asks for a fresh token (`dsh web` prints a new one on every restart).

## 2. Unary RPC

```text
POST {baseUrl}/api/{namespace}/{method}
Content-Type: application/json
Cookie: dsh-auth-…=…
Authorization: Bearer <local token>      // relay gateway only
```

```json
{ "type": "client-request", "rpcId": "dsh-g1-12", "method": "session/prompt",
  "payload": { "args": { "request": { … } } } }
```

`payload` always carries exactly one `args` object whose keys are the **parameter names** of the Remote method.
Responses:

```json
{ "type": "server-response", "rpcId": "dsh-g1-12", "result": { "ok": true, "value": { … } } }
{ "type": "server-response", "rpcId": "dsh-g1-12", "result": { "ok": false, "error": { "code": "session/title-invalid", "message": "…", "details": { } } } }
```

Void results omit `value`; the runtime hands callers an empty object. Timeout 30 s (120 s for prompts carrying images).
Transport failures surface as `transport-{httpStatus}`; a lost connection generation as `generation-cancelled`.

### Endpoint catalogue (what the App sends)

| Endpoint | `args` | Purpose |
| --- | --- | --- |
| `session/list` | `{ "_request": {} }` | Session rows: `sessionId`, `updatedAt`, `running`, `blank`, `cwd`, `projections.values.title` |
| `session/create` | `{ "request": { "workspaceId"? } }` | → `{ sessionId }` |
| `session/prompt` | `{ "request": { "requestId", "sessionId", "mode": "queue", "content": [...], "clientTimeZone" } }` | Send a user turn (§4) |
| `session/cancel` | `{ "request": { "sessionId" } }` | Stop the running turn |
| `session/rename` | `{ "request": { "sessionId", "title" } }` | → `{ title, seq }`; blank title → `session/title-invalid` |
| `session/fork` | `{ "request": { "sessionId", "atSeq"? } }` | → `{ sessionId }` |
| `session/attachment` | `{ "request": { "sessionId", "attachmentId" } }` | → `{ attachment: ImageAttachmentRef, data: base64 }` |
| `session/updateQueue` | `{ "request": { "sessionId", "itemId", "action" } }` | `edit` / `remove` / `steer` |
| `session/page` | `{ "request": { "address", "throughSeq", "beforeSeq"?, "maxMessages"? } }` | Older history pages |
| `session/modelCatalog` | `{}` | `{ default, routableProviders, groups[], failures[] }` |
| `session/selectModel` | `{ "request": { "sessionId", "provider", "model", "reasoningEffort"? } }` | → `{ selected }` |
| `skills/list` | `{ "request": { "sessionId" } }` | `/` completion |
| `pluginInventory/list` | `{}` | Read-only plugin inventory (`entries[]`, `agentPresets[]?`) |
| `workspace/create` / `rename` / `delete` / `insertBefore` / `archiveSession` | `{ "request": { … } }` | Workspace registry mutations |
| `directoryPicker/list` | `{ "path"? }` | Directory browser |
| `directoryPicker/createDirectory` | `{ "path", "name" }` | → path string |
| `goals/edit` | `{ "agentId", "ref": { id, revision }, "request": { objective } }` | `agentId` is the session id |
| `goals/pause` / `resume` / `clear` | `{ "agentId", "ref" }` | |
| `credentials/describe` | `{ "refs": [...] }` | API-key status |
| `credentials/set` | `{ "ref", "value" }` | Write the Host API key |
| `llm/listProviders`, `settings/describe` | `{}` | Provider / settings facts |
| `$events/result` | `{ "clientId", "eventId", "outcome" }` | Answer a waterfall (§5) |

Export is not an RPC: `GET /api/session.export?sessionId=&includeDescendants=` (cookie required).

## 3. Streams: `/api/remote.mux`

One WebSocket carries independently cancellable logical streams:

```json
→ { "type": "open",   "streamId": "stream-…", "endpoint": "session/follow", "payload": { "args": { … } } }
→ { "type": "cancel", "streamId": "stream-…" }
← { "type": "item",   "streamId": "stream-…", "value": { … } }
← { "type": "end",    "streamId": "stream-…" }
← { "type": "error",  "streamId": "stream-…", "error": { "code", "message", "details" } }
```

The Host pings every 2 s; the phone answers at the protocol layer. Any socket close ends every logical stream.

### Ready sequence (one connection generation)

1. Ensure a cookie (§1).
2. Open the socket; on `OPEN` open **`$events`** with `{ "args": {} }`. Its first item must be
   `{ "type": "ready", "clientId", "host": { "home" } }`.
3. Open **`session/control`** `{}` → first item `{ "type": "baseline", "value": { "queues", "jobs", "projections" } }`.
4. Open **`workspace/follow`** `{}` → first item `{ "type": "baseline", "value": { "items", "archivedSessionIds" } }`.
5. Call `session/list`.
6. `READY` once all four arrived; queued RPCs and stream opens are flushed. On any failure the generation is
   invalidated and retried after 1 s; `401`/`403` on the upgrade goes through the re-mint path instead.

### `$events`

| Frame | Meaning |
| --- | --- |
| `{ "type": "emit", "event": "api-session/added", "args": [summary] }` | New session row |
| `emit api-session/removed [sessionId]` | Row removed; the App drops its follow and caches |
| `emit api-session/status [sessionId, running]` | Turn started / finished |
| `emit api-session/activity [sessionId, updatedAt]` | List ordering hint |
| `emit api-session/error [sessionId, message]` | Agent failure outside a durable turn |
| `emit settings/document-updated`, `llm/adapters-updated`, `commands/change`, … | Catalog invalidation |
| `{ "type": "waterfall", "event": "approval/request" \| "user-questions/request", "eventId", "agentId", "request" }` | Pending interaction; `agentId` **is** the session id |
| `{ "type": "cancel", "eventId" }` | The Host withdrew a pending waterfall |

### `session/control`

`baseline` → then `{ "type": "queue", "sessionId", "items" }`, `{ "type": "jobs", "sessionId", "jobs" }`,
`{ "type": "projection", "sessionId", "key", "value", "seq" }`. Projection keys the App reads: `title`, `goal`,
`modelSelection` (`{ lastUsed, next }`), `imageLimits` (§4), `sessionListMetadata`.

### `workspace/follow`

`baseline` → then `upsert { workspace }`, `remove { workspaceId }`, `order { workspaceIds }`, `archived { archivedSessionIds }`.

### `session/follow` (per observed session)

```json
{ "args": { "request": { "address": { "kind": "session", "sessionId": "…" }, "maxMessages": 80, "assistantStream": true } } }
```

| Frame | Meaning |
| --- | --- |
| `{ "type": "snapshot", "header", "cursor", "records": [{ "type": "event", "event" }], "hasMore", "projections", "assistantStream"? }` | Opening page; `assistantStream.activeAttempt.stream` replays an in-flight reply after reconnect |
| `{ "type": "event", "event": { "type", "seq", "time", "data" } }` | One durable event, gap-free after the snapshot cursor |
| `{ "type": "assistant-stream", "frame": { "type": "start" \| "chunk" \| "end", … } }` | Process-local model stream (only with `assistantStream: true`) |

Durable event types the App folds (`DshWebTimelineParser`, `DshRemoteToolCallModels`):

| `event.type` | Use |
| --- | --- |
| `user/message` | User bubble (text + `{type:"image", attachment: ImageAttachmentRef}` blocks) or context injection when `source.kind != user` |
| `assistant/message` | Committed reply blocks: `text`, `reasoning`, `image`, unknown → JSON card |
| `assistant/attempt` | Failed attempt (stream only) |
| `tool/call` + `tool/result` | One tool card; `tool/result.meta.diffs` feeds the diff view |
| `turn/start` / `turn/end` | `turn/end.reason.kind`: `completed` \| `aborted` \| `error` (`reason.error.message`) |

`assistant-stream.chunk.chunk` is an LLM `StreamChunk`: `text-delta` / `reasoning-delta` drive the live bubble,
`finish` carries the failure when `reason.kind == "error"`. `end.outcome` names the committed `assistant/message` seq
(or `abandoned`).

## 4. Sending a turn

```json
{ "args": { "request": {
  "requestId": "mobile-…",                 // client-minted, echoed as user/message.source.rpcId
  "sessionId": "…",
  "mode": "queue",
  "content": [
    { "type": "text", "text": "…" },
    { "type": "image", "mediaType": "image/png", "data": "<canonical base64>", "name": "photo.png" }
  ],
  "clientTimeZone": "UTC" } } }
```

The HTTP result is only a receipt (`{ accepted: true }`); the reply arrives on the session's `session/follow`
stream. The App matches `user/message.source.rpcId` to its `requestId`, then routes `assistant-stream` chunks and
`tool/*` events for that session into the live bubble until `turn/end`.

Images: only PNG / JPEG / WebP / GIF; limits come from the `imageLimits` projection
(`maxImageBytes`, `maxImagesPerMessage`, `maxMessageImageBytes`, `maxImagePixels`, `maxImageDimension`,
`mediaTypes`) and are checked on the phone before sending by `DshAttachmentPrevalidation`, which reports the
same `details.reason` codes the Host would. `session/prompt` answers only a receipt, and that receipt is the
point where the Host has validated and stored the images — the composer shows "sending" until it lands.
A rejected prompt appends no `user/message`, so nothing durable exists to display. Rejections arrive as
`session/attachment-invalid` with `details.reason` ∈ `TOO_MANY_IMAGES`, `IMAGES_TOO_LARGE`, `IMAGE_TOO_LARGE`,
`IMAGE_TOO_MANY_PIXELS`, `IMAGE_DIMENSION_TOO_LARGE`, `UNSUPPORTED_IMAGE_TYPE`, `INVALID_IMAGE_BASE64`,
`IMAGE_TYPE_MISMATCH`, `MODEL_DOES_NOT_SUPPORT_IMAGES`. The durable log keeps only the `ImageAttachmentRef`
(`attachmentId`, `mediaType`, `bytes`, `width`, `height`, `name?`); bytes are read back with `session/attachment`.

## 5. Approvals and questions

Both are Host waterfalls forwarded over `$events`; the App is one answerer in the chain.

```json
← { "type": "waterfall", "event": "approval/request", "eventId": "…", "agentId": "<sessionId>",
    "request": { "toolName": "bash", "callId": "call_…", "reason": "…" } }
→ POST /api/$events/result  { "args": { "clientId": "<from ready>", "eventId": "…",
    "outcome": { "kind": "result", "value": "allowed-once" | "rejected" } } }
```

```json
← { "type": "waterfall", "event": "user-questions/request", "eventId", "agentId",
    "request": { "questions": [{ "id", "question", "header"?, "detail"?, "options"?: [{ label, description? }], "multiSelect"? }] } }
→ outcome.value = { "answers": [{ "id", "selected": [...], "custom"? }] }
```

`{ "kind": "next" }` defers to the Host default (used for unknown waterfalls); `{ "kind": "rejected", "error" }`
fails the request. A `cancel` frame removes the pending card.

## 6. Code map

| File | Role |
| --- | --- |
| `DshHostProtocol.kt` | Endpoint constants, `args`/`request` wrappers, `DshHostConnection`, timeline parser, `ImageAttachmentRef` extraction |
| `DshHostRuntime.kt` | `DshHostAuthenticator`, `DshRemoteMux` (logical streams), `DshHostConnectionRuntime` (auth, generations, ready sequence, unary) |
| `DshRemoteHostRepository.kt` | Frame reducers for `$events` / control / workspace / follow, live prompt streams, waterfalls, model catalog, plugin inventory |
| `DshRemoteRepository.kt` | Page-facing facade + `DshStoredHostAuthenticator` (cookie persistence, token source) |
| `DshWebSocketModule.kt`, `BridgeModule.mintAuthCookie` | Native bridges (cookie header, `send`, redirect-free cookie minting) |
| `DshMediaModule.kt`, `KRDshMediaModule.kt`, `HRDshMediaModule.m` | Photo-library and camera sources; Base64 + `mediaType`/bytes/pixels only |
| `DshAttachmentPrevalidation.kt` | `imageLimits` enforcement with the Host's reason codes |
| `tools/mock-host` | Node replay Host speaking exactly this protocol |

Upstream references (tag `dsh-v0.1.5-rc.1`): `packages/api/gateway/src/stream-protocol.ts`,
`packages/api/session-controller/src/types.ts`, `packages/api/workspace-controller/src/types.ts`,
`packages/client/connection/src/browser-auth.ts`, `packages/attachment/attachment/src/{types,error}.ts`,
`packages/host/plugin-inventory/src/types.ts`, `packages/interaction/user-approval/src/types.ts`,
`packages/interaction/user-questions/src/types.ts`.
