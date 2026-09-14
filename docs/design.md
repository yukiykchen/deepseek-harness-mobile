# DSH App — design notes

The four topics the delivery requirements ask for: the message model, the streaming state
machine, the attachment protocol, and the export format. The wire protocol itself lives in
[`app-host-protocol.md`](app-host-protocol.md) and is not repeated here; this document is
about what the App does with it.

Everything described below is in `shared/src/commonMain/kotlin/com/example/dsh/dsh/`, so it
is shared by Android, iOS and OpenHarmony. The platform layers hold only bridges: sockets,
the cookie mint, pickers, clipboard, share and print.

---

## 0. Protocol baseline, and why the task's reference links 404

The task description references `packages/host/apiproxy/src/api/sessions.ts` and friends on
`master`. That package no longer exists. It was present at `dsh-v0.1.1-rc.2` and was removed
before 0.1.5, replaced by `packages/api/{gateway,session-controller,workspace-controller}`.
Verifiable in an upstream clone:

```bash
git ls-tree --name-only dsh-v0.1.1-rc.2 packages/host/   # lists apiproxy
git ls-tree --name-only HEAD             packages/host/   # apiproxy is gone
git grep -n "events.mux" HEAD                             # no matches at 0.1.5
git grep -n "remote.mux" HEAD -- packages/api/gateway/src # REMOTE_STREAM_MUX_PATH
```

This App targets **DSH 0.1.5** and speaks the current protocol. The mapping from the names in
the task description to what the Host actually exposes today:

| Task description (apiproxy, ≤ 0.1.1) | DSH 0.1.5 equivalent |
| --- | --- |
| `GET /api/events.mux`, `/api/events.host` (SSE / WS) | one WebSocket `/api/remote.mux` carrying named logical streams |
| `Authorization: Bearer <token>` on every call | browser-session cookie minted by `GET /?token=<launch token>`; the bearer only crosses the phone's own relay gateway |
| `session.prompt` | `POST /api/session/prompt`, `client-request` envelope, `payload.args.request` |
| `sessions.attachment` | `session/attachment` |
| `session.rename` | `session/rename` |
| `workspace.archiveSession` | `workspace/archiveSession` |
| `pluginInventory/list` | unchanged, still read-only |
| `POST /api/respond` (waterfall answers) | `POST /api/$events/result` with `{clientId, eventId, outcome}` |

The semantics the task asks for are unchanged — the image pipeline is still
`content: [{type:"image", mediaType, data, name}]` and the durable log still keeps only an
`ImageAttachmentRef`. Only the transport and the method names moved. Legacy support was
dropped deliberately rather than carried: see §5.

---

## 1. Message model

`DshMessage` (`DshModels.kt`) is one flat row in a conversation. The App deliberately does
**not** mirror the Host's event graph; it projects that graph into an ordered list of rows,
because the UI is a list and every reordering bug this project hit came from the two
representations disagreeing.

```kotlin
data class DshMessage(
    val id: String,
    val role: DshMessageRole,        // USER | ASSISTANT | TOOL | ERROR
    val content: String,             // Markdown source, never rendered output
    val streaming: Boolean = false,
    val hidden: Boolean = false,
    val isReasoning: Boolean = false,
    val isContextInjection: Boolean = false,
    val toolName: String?, val toolRunning: Boolean, val toolError: Boolean,
    val remoteTool: DshRemoteToolCallModel?,   // structured tool card state
    val attachments: List<DshImageAttachmentRef>,
    val attachmentId: String?,       // an assistant-produced image
    // … context-injection detail fields
)
```

Three rules hold everywhere:

1. **`content` is source, not presentation.** Markdown stays Markdown and `$…$` stays LaTeX.
   Rendering happens in the view (`DshMarkdownView`, `DshLatex`), so copy and export can
   hand back exactly what the model holds. This is the single reason the LaTeX feature did
   not disturb Task 2.
2. **A row maps to at most one durable Host event.** A `tool/call` plus its `tool/result`
   collapse into one row, because they are one card on screen.
3. **Identity is stable.** Rows are patched in place by id, never rebuilt, so the list view
   keeps its scroll position and its child views.

Rows come from two sources that must agree: the **durable timeline** parsed from
`session/follow` records (`DshWebTimelineParser`), and the **live projection** built from
`assistant-stream` chunks while a turn is running. `applyMessagesInPlace` reconciles them.

> The hardest bug in the project lived exactly here. `applyMessagesInPlace` patches by index,
> and Kuikly's `vforLazy` mishandles a set at the tail (`ObservableList.set` is a remove plus
> an add, and the add branch creates no child when `index >= currentEnd`). When the live
> projection produced a different number of rows than the durable log, content shifted into
> the wrong rows. The fix was to make the two agree — see §2 — not to patch the list view.

## 2. Streaming state machine

One turn, per session. The state lives in `DshHomePage` as plain observables rather than an
enum, because several of them are read independently by the view; the states below are the
meaningful combinations.

```
      ┌──────────────────────────────── turn/end ◄──────────────────────────┐
      │                                                                     │
   IDLE ──sendDraft──► PROMPTED ──first chunk──► STREAMING ──tool/call──► TOOL
      ▲    (receipt)       │                        │  ▲                  │
      │                    │                        │  └──tool/result─────┘
      └────────────────────┴────── error ───────────┘
```

| State | Entered by | What the UI shows |
| --- | --- | --- |
| `PROMPTED` | the `session/prompt` receipt | the user bubble, plus a turn-status line. The receipt — not the first token — is what ends "sending", because that is when the Host has validated and stored the turn |
| `STREAMING` | `assistant-stream` `chunk` | one live assistant row, appended to as `text-delta` / `reasoning-delta` arrive |
| `TOOL` | a durable `tool/call` | the live row is **sealed** and a tool card is appended after it |
| settle | `turn/end` | the live row is replaced by the durable `assistant/message`; `reason.kind == error` keeps the partial reply *and* appends the error row, mirroring the Host |

Key fields: `streamingAssistantId` (the live row), `streamingAssistantRootId` and
`streamingAssistantSegment` (a turn can own several rows when tools interleave),
`streamingReasoningId`, `pendingAssistantDelta`, and `assistantBlocksProjected`.

Four invariants, each of which was a bug first:

- **Sealing is exclusive.** `splitStreamingAssistantBeforeTool()` returns whether it sealed a
  row; `showAssistantBlocks` appends a row for a committed `text` block only when it did
  *not* seal one. Otherwise a committed message whose text was also streamed produced two
  rows and the index-wise patch shifted everything after it.
- **The turn accumulator is a fallback, not a source.** `assistantBlocksProjected` stops
  `onComplete` appending the turn-wide accumulator when the block path already produced rows.
- **A failed turn keeps its partial reply**, because the durable log does. `settleFailedTurn`
  mirrors that, so live and durable have the same row count.
- **What is painted is what is copied.** `dshDisplayedAssistantContent(stored, live, isLiveRow)`
  is used by both the renderer and `DshMessageExport`, so copying mid-stream matches the screen.

**Reconnect.** A connection generation owns its streams. On loss, follows are cancelled and
in-flight prompt streams are kept; on the new generation the `session/follow` snapshot carries
`assistantStream.activeAttempt.stream`, which is replayed to re-adopt a turn that kept running
on the Host while the phone was away. Nothing is invented client-side: if the Host has no
active attempt, the durable records are the truth.

**Rendering.** `DshMarkdownView` coalesces updates to one frame (16 ms), parses with
KuiklyMarkdown's `MarkdownStreamingState`, and closes an open fence for display only so a
half-arrived code block does not flash as a paragraph. `DshLatex.substituteInline` runs in the
same place for inline formulas; a standalone `$$…$$` block is routed to a DSH-owned view.
Only *closed* formulas convert, which is what makes a formula split across chunks safe.

## 3. Attachment protocol

The App uses the official image pipeline and defines nothing of its own.

```
pick / capture ──► DshMediaResult(base64, mediaType, w, h, bytes)
                        │
                        ▼
        DshAttachmentPrevalidation.validate(candidate, staged, imageLimits)
                        │ ok                              │ rejected
                        ▼                                 ▼
   session/prompt content:[{type:"image", …}]      reason code + readable copy,
                        │                          nothing is sent
                        ▼
            receipt ──► Host validates and stores
                        │
                        ▼
   durable user/message carries only ImageAttachmentRef
   {attachmentId, mediaType, bytes, width, height, name?}
                        │
                        ▼
        session/attachment(attachmentId) ──► bytes, on demand
```

- **Limits are the Host's.** `imageLimits` arrives as a `session/control` projection
  (`maxImageBytes`, `maxImagesPerMessage`, `maxMessageImageBytes`, `maxImagePixels`,
  `maxImageDimension`, `mediaTypes`). `DshAttachmentPrevalidation` mirrors them and reports the
  Host's own `details.reason` codes, so the message a user sees before sending is the message
  they would have seen after. `dshAttachmentReasonMessage` is the single copy table.
- **Oversize files never cross the bridge.** The picker is given `maxBytes` and returns metadata
  with empty `data` when a file already exceeds it, so a 27 MB PNG costs no 36 MB base64 string
  for a rejection that only needs its byte count.
- **Nothing local is persisted.** A staged image carries a client-only `localId`/`previewKey` so
  an optimistic bubble can paint, while `attachmentId` stays empty until the Host commits one.
  The SQLite cache stores no attachment rows: after a restart the `session/follow` snapshot
  supplies the refs and `session/attachment` supplies the bytes. No local path, temporary URL,
  or raw base64 is ever written to conversation history.
- **General files are not supported**, because that needs a Host protocol extension and the
  requirement forbids claiming it otherwise. The composer offers no dead "file" entry point.

## 4. Export format

One module, `DshMessageExport`, is the only place rows become text. Three containers share a
single content pass, so they cannot drift:

| Container | Entry point | Used by |
| --- | --- | --- |
| Markdown | `conversation(title, rows)` / `message(row)` / `transcriptEntry(row)` | copy message, export conversation, batch export |
| HTML | `html(title, rows)` | "Export as HTML" → `.html` file in the share sheet |
| PDF | the same HTML | "Print / save as PDF" → `PrintManager`, whose *Save as PDF* destination writes the file |

Shape of the Markdown container:

```markdown
# <conversation title>

## You
<prose>
[image: photo.png, 256×256, 136 KB, attachment att_…]

## DeepSeek
<prose, code fences intact>

## Tool · Grep — TODO (failed)
```
<tool output>
```
```

Rules, all of which are acceptance criteria:

- **Order is the timeline order**, including tool cards and attachments interleaved between
  prose. Hidden and empty rows are dropped; nothing is re-sorted.
- **Structured cards export as readable text**, carrying name, status and body — never the JSON
  the card was built from.
- **Attachments export as a reference line** with filename, dimensions, byte size and
  `attachmentId`, never base64.
- **Code copies clean.** `codeBlocks(markdown)` lifts fenced blocks for "copy code block N",
  handles ``` and `~~~`, and yields the body of an *unterminated* fence so a streaming reply is
  still copyable.
- **Formulas export as LaTeX source**, in all three containers, matching "copy formula N". The
  Unicode approximation is a display concern and never reaches the model.
- **HTML is self-contained**: inlined CSS, no network, a fixed light palette, and a print
  stylesheet so a saved PDF is not a black rectangle. Message text is escaped, so a reply
  containing `<script>` exports as text.

The log centre reuses these conventions for its issue bundle, and redacts on the way *in*, so
the ring buffer, the detail view, the clipboard and the export are redacted by construction.

## 5. Decisions worth naming

- **0.1.5 only, no legacy fallback.** Supporting both protocols would have doubled the
  transport surface for a Host version that is no longer published. The mapping in §0 is the
  compensation: the reference links in the task still resolve to something concrete.
- **Reload from the Host rather than widen the cache.** Attachments and the log centre both
  chose this. The SQLite cache is a cold-start convenience for text, not a second source of
  truth; the Host is the only one.
- **Only capabilities that exist.** Archive is offered because `workspace/archiveSession`
  exists; the plugin inventory is read-only because `pluginInventory/list` is. Where a
  capability needs a Host extension, it is either shipped as an installable plugin or not
  offered at all — never faked client-side.
- **Theme tokens, not literals.** `DshPalette` carries 43 semantic tokens read through
  `PagerScope.theme` inside `attr { }`, which is reactive per pager. `DshThemeContrastTest`
  holds WCAG ratios for every text token in every mode, which is how two real legibility
  defects were caught.
