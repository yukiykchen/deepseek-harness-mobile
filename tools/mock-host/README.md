# dsh-mock-host

A replay-driven mock of the DeepSeek Harness **0.1.5** Host wire protocol, so the
mobile app can be developed, demoed and tested without a real DSH, Relay or API key.

It speaks exactly what the app speaks:

| Surface | Behaviour |
| --- | --- |
| `GET /?token=<launch token>` | Mints the authority-bound browser-session cookie (`303` + `Set-Cookie`), like `@deepseek-ai/dsh-client-connection`. Every `/api` request and the WebSocket upgrade require it (`--no-auth` disables the check). |
| `POST /api/<namespace>/<method>` | `client-request` / `server-response` envelope with Typert `{ "args": { … } }` payloads and the exact 0.1.5-rc.1 parameter names (`request`, `_request` for `session/list`, `agentId` for goals, `refs`/`ref`/`value` for credentials). |
| `WS /api/remote.mux` | Logical streams `open` / `cancel` → `item` / `end` / `error`: `$events` (ready, `api-session/*` emits, approval and user-question waterfalls, cancel), `session/control` (baseline + queue/jobs/projection frames, including `imageLimits`), `workspace/follow`, `session/follow` (snapshot + durable events + `assistant-stream` start/chunk/end). |
| `GET /dsh-scan-remote/api/auth` | Same shape the patched relay plugin serves: `{ authority, token }`. |
| `GET /api/session.export?sessionId=` | The raw journal as JSON. |

## Run

```bash
cd tools/mock-host
npm install
npm start -- --host 0.0.0.0 --port 3080 --token dev-token
```

The banner prints the authenticated URL, the launch token and the scenario keywords.
Point the app at it with **Direct (dev)** in the connection page (use your Mac's LAN IP
or `10.0.2.2` from the Android emulator) and paste the token.

Options: `--speed 3` streams faster, `--verbose` logs every RPC and stream open.

## Scenarios

The assistant's reply is chosen by keywords in the prompt (`fixtures/scenarios/*.json`):

| Keyword | Exercises |
| --- | --- |
| *(default)* | Headings, lists, quote, table, links, fenced code, reasoning block |
| `latex`, `math` | Inline `$…$`, block `$$…$$`, fractions, Greek letters, a matrix, a formula split across chunks, an unclosed formula |
| `tool` | Interleaved text + `bash` / `read` / `edit` (with diff `meta`) / failing `grep` cards |
| `image` | Echoes the uploaded image back as an assistant image block (`session/attachment` round trip) |
| `fail`, `error` | Partial output, then `turn/end` with `reason.kind = error` |
| `approve` | `approval/request` waterfall on `$events`, answered with `$events/result` |
| `ask` | Two `user-questions/request` questions (single + multi select) |
| `long` | Long, fast stream for scroll and log-center throughput |

Prompts with images are validated against `fixtures/image-limits.json` and rejected
with `session/attachment-invalid` + `details.reason` (`UNSUPPORTED_IMAGE_TYPE`,
`IMAGE_TOO_LARGE`, `TOO_MANY_IMAGES`, …) exactly like the Host. Two seeded sessions are
pre-loaded (`fixtures/seed-sessions.json`), one of them archived.

`session/rename` with a blank title fails with `session/title-invalid`; `session/cancel`
ends the running turn with `reason.kind = aborted`.

## Test

```bash
npm test
```

`test/server.test.js` drives the server end to end over HTTP + WebSocket: cookie
minting, envelope validation, a full scripted turn (contiguous `seq`, request-id
echo, tool cards, assistant-stream frames), image admission and read-back, approval
waterfalls, and cancellation.
