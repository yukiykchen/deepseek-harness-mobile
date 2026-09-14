# Requirements checklist

Self-assessment against `DSH_App_Project_Requirements_EN.md`, one line per acceptance
criterion and per bonus. Legend: **Yes** = implemented and driven at runtime against the mock
Host on an Android emulator; **Code** = implemented and compiling, path not photographed;
**Partial** = qualified, reason given; **No** = not done, reason given.

Unless noted, runtime verification means Direct mode against `tools/mock-host` on
`emulator-5554`. Screenshots for each are listed in `HANDOFF.MD`.

---

## Task 1 · Dark mode and system-theme adaptation

| # | Criterion | Status | Evidence |
| --- | --- | --- | --- |
| 1 | Light, dark and follow-system modes | **Yes** | `DshThemeMode` has five modes — the three required plus high contrast and sunrise-to-sunset |
| 2 | Takes effect immediately, survives restart | **Yes** | `SharedPreferencesModule`; `adb shell cmd uimode night yes/no` flips System mode live without recreating the activity; Dark survives a cold restart |
| 3 | Code, formulas, tool states and error colours readable in dark | **Yes** | 43 semantic tokens; `DshThemeContrastTest` asserts WCAG ratios for every text token in all modes |
| 4 | No white flashing, unreadable text or missing icons | **Yes** | `values-night` + native window background + status-bar glyphs; `tintColor` on all 18 monochrome icons |
| B1 | Independent code theme | **Partial** | Applies to the DSH-owned listings (tool output, diffs, JSON). KuiklyMarkdown 1.0.6 paints inline code and fenced blocks with one shared foreground and never paints the inline background chip, so Markdown fences must follow the app theme. The modal says so |
| B2 | Auto-switch by sunrise and sunset | **Yes** | `DshSolarClock`: NOAA declination at the civil zenith, device UTC offset, fixed latitude. Approximate by up to an hour and the UI says so — no location permission is requested |
| B3 | Accessible high-contrast theme | **Yes** | `HIGH_CONTRAST` palette; the contrast test asserts it beats plain dark and clears AAA for body text |

## Task 2 · Text selection, copying and export

| # | Criterion | Status | Evidence |
| --- | --- | --- | --- |
| 1 | Select any passage and copy it | **Yes** | Long-press → "Select text" → Kuikly `createSelection(WORD)` on the row container, native drag handles, floating Copy |
| 2 | One-tap copy of the complete body, no UI copy | **Yes** | `DshMessageExport.message`; verified by pasting back into the composer |
| 3 | Copying a code block copies only code | **Yes** | Paste ends without the closing fence, unlike copy-message |
| 4 | Copied output keeps the order of text, cards and results | **Yes** | Single ordered pass over the row list; hidden and empty rows dropped |
| 5 | Cards and attachments export as readable text with name, status, filename, reference | **Yes** | `## Tool · Grep — TODO (failed)` + fenced body; `[image: name, W×H, size, attachment <id>]` |
| B1 | Export as PDF or HTML | **Yes** | Both. `.html` file through the share sheet; PDF through `PrintManager`, whose *Save as PDF* destination was photographed |
| B2 | Select multiple messages, export in one batch | **Yes** | "Select messages" mode, tinted rows, Copy / Export bar; exported two rows in timeline order |

## Task 3 · Image and file-attachment uploads

| # | Criterion | Status | Evidence |
| --- | --- | --- | --- |
| 1 | Image button; send a PNG/JPEG/WebP/GIF with text | **Yes** | `MediaStore.ACTION_PICK_IMAGES` on 33+, SAF below |
| 2 | Camera photo, readable prompt when denied | **Yes** | 1392×1856 JPEG sent through the same pipeline; denial shows "Camera access is off…" |
| 3 | Preview and remove before sending; progress from the whole `session.prompt`; no custom chunking | **Yes** | Sending ends on the prompt receipt, which is when the Host has stored the images |
| 4 | After failure, remove or retry, readable message | **Yes** | Killing the Host mid-send preserved the staged images; they sent after reconnect |
| 5 | Host user message carries name, mediaType, bytes, attachmentId; no raw base64 in the event | **Yes** | Durable `user/message` holds only `ImageAttachmentRef` |
| 6 | Reopening restores the image from its historical `attachmentId` | **Yes** | Cold restart restored both images purely from `attachmentId` |
| 7 | Enforce `imageLimits` before sending, with an explanation | **Yes** | A 27 MB PNG was rejected as `IMAGE_TOO_LARGE` before any request was made |
| B1 | Thumbnails and full-screen viewing | **Yes** | 72 px staged tiles, 96 px bubble tiles, full-screen viewer |
| B2 | Multiple images in one message | **Yes** | Two images in one `images=2` turn |
| B3 | Prevalidation aligned to Host error codes | **Yes** | `DshAttachmentReason` names are the Host's `details.reason` verbatim |
| B4 | General files such as PDF | **No** | Needs a Host storage and reference-format extension. The requirement forbids claiming it otherwise, so the composer's placeholder "file" row was removed rather than left dead |

## Task 4 · Conversation delete, rename and archive

| # | Criterion | Status | Evidence |
| --- | --- | --- | --- |
| 1 | Rename persists and matches the Host; empty title fails with `title-invalid` | **Yes** | Correct after two full app restarts; the Host's `session/title-invalid` is shown, not a local guess |
| 2 | Archived hidden from the main list, openable from an archive list with full history | **Yes** | `follow.snapshot session=seed-archived-notes records=6` |
| 3 | Archiving requires confirmation and says it hides rather than deletes | **Yes** | `DshArchiveConfirmModal` |
| 4 | Archiving the open conversation switches to the first unarchived one | **Yes** | Switched to a blank conversation; the archived-set subtraction stops it landing on another archived row |
| 5 | On failure the list is unchanged and an error is shown | **Code** | `dshSessionErrorMessage` in the modal; the mock only fails `archiveSession` for an unknown id, so the path was not triggered |
| 6 | Local mode disables or explains the unavailable actions | **Code** | `DshDisabledSheetRow` with the reason; reaching it needs the Host down *and* a session row on screen |
| B1 | Restore from archive via a Host extension | **No**, investigated | `WorkspaceRegistry` has `archiveSession` and an `archivedSessionIds` getter and **nothing that removes an id**. Upstream states it: *"Archiving is one-way — … no unarchive action exists yet"* (`packages/workspace/workspace/README.md`). The set is in `$DSH_HOME/storages/workspace.json`, but the registry caches it and every write is `{...this.state, archivedSessionIds: […]}`, so a direct file write would be invisible to the running Host and then silently discarded. Declined rather than shipped as a data-loss bug; see [`host-plugin/README.md`](../host-plugin/README.md) |
| B2 | Permanent deletion via a Host extension | **No**, investigated | No delete exists at any layer; `SessionPersistence` has no removal. A purge would have to touch the JSONL log and its write lock, workspace membership, the archive set, the projection cache, and content-addressed attachments **shared by hash** across sessions |
| B3 | Sort by recency, creation time and name | **No** | Not attempted |
| B4 | Lightweight undo after archiving | **No** | Requires B1. The requirement forbids a fake undo where no restore API exists, so none is offered |

## Task 5 · Plugin menu and plugin-status display

| # | Criterion | Status | Evidence |
| --- | --- | --- | --- |
| 1 | Retrieve and display the Host's plugin list | **Yes** | `pluginInventory/list`, probed with curl before any UI was written |
| 2 | Search by name, filter by status | **Yes** | Search narrowed to two rows and to none; all six lifecycle statuses present as chips with counts |
| 3 | A failed plugin shows its status and a readable summary | **Yes** | Failed row first and bordered in the danger colour. The inventory carries no failure text (`PluginInventoryEntry` is `{entryId, moduleName, enabled, fiberPhase}`), so the row says the phase is all the Host reports and names where the cause is |
| 4 | Clear empty, loading, failure and no-results states | **Partial** | Loading, request-failure and no-results driven at runtime; the empty-list state is the same block shape but reaching it needs an edited fixture |
| 5 | No enable/disable buttons that cannot work | **Yes** | Read-only by default with the header saying why; controls appear only for entries the companion plugin advertises as controllable |
| B1 | Details page and full configuration view | **Yes** | Rows expand in place into Module / Configuration / Lifecycle |
| B2 | Refresh plugin status | **Yes** | Retry restored the list after killing the Host mid-refresh |
| B3 | Custom Host protocol to start, stop and reload | **Yes** | [`host-plugin/`](../host-plugin/) — an installable Cordis plugin, 14 tests, [protocol documented](dsh-mobile-admin-protocol.md). Verified end to end: Disable → confirmation naming the module → *Disabled* with no live root fiber → Enable → *Running*, with the change visible in the **official** `pluginInventory/list` too. Authenticated with `connection.requestRejection`, so the route is exactly as hard to reach as `/api`; loopback is not treated as authentication |

## Task 6 · Log centre and issue feedback

| # | Criterion | Status | Evidence |
| --- | --- | --- | --- |
| 1 | Records connection, disconnect, retry, RPC start and failure, mux/host frames, conversation errors | **Yes** | 75 records from one connect plus one tool turn |
| 2 | Conversation logs carry `sessionId`, time and event type | **Yes** | Detail view shows ISO UTC time, level, event, session and ref |
| 3 | Filter by `assistant/chunk`, `tool/call`, `tool/result`, `turn/end`, reconnect and Error | **Yes** | Durable events keep their Host type, so these filters are real rather than a client invention |
| 4 | Exported text is redacted; details and copies redacted the same way | **Yes** | `DshLogRedaction` runs on write, so buffer, detail, clipboard and export are redacted by construction |
| 5 | Bounded memory; opening the page during generation does not lag the conversation | **Yes** | 1000-entry ring buffer, 200 painted rows, snapshot-on-open rather than a live feed |
| 6 | Clearing removes local logs only | **Yes** | Conversation untouched, fresh records arrived after |
| B1 | One-tap issue bundle | **Partial** | Bundle carries redacted logs, connection mode and phase, platform, app version and the active filter. **Device model is omitted** — Kuikly's `PageData` has no model field |
| B2 | Crash capture shown at next launch | **No** | Not attempted |
| B3 | Jump from a log record to its conversation | **No** | Not attempted |

## Bonus task · Markdown and LaTeX rendering

| # | Criterion | Status | Evidence |
| --- | --- | --- | --- |
| 1 | Inline `$E=mc^2$` displays correctly | **Yes** | Renders as `mc²` |
| 2 | Block `$$x^2+y^2=z^2$$` laid out independently | **Yes** | Routed out of KuiklyMarkdown to its own DSH-owned row |
| 3 | A formula split across chunks does not crash, lose the message or corrupt layout | **Yes** | Only *closed* spans convert, so a partial formula is simply still text; 9 stable blocks through the whole stream |
| 4 | Invalid or unclosed formulas fall back to plain text | **Yes** | Unclosed `$a + b = c` stays text; an unknown command falls the block back to its source |
| 5 | Scrolling and refresh stable with Markdown, formulas and code mixed | **Yes** | `render.apply … uiBlocks=9→9` with no remount churn |
| B1 | Copy formulas as source text | **Yes** | "Copy formula N" put `x^2 + y^2 = z^2` on the clipboard, not the rendered form |
| B2 | Matrices, fractions, super/subscripts, Greek | **Yes** | `α₁ = (β₂ + γ₃)⁄√δ · Σᵢ₌₁ⁿ xᵢ` and a 2×2 matrix on two rows |
| B3 | Formula and code themes adapt to dark mode | **Yes** | Formula blocks repaint with the dark code palette |

Known limit: Unicode has no superscript pi or q, so a script group that cannot map keeps caret
notation (`e^{i\pi}` renders as `e^(iπ)`) instead of failing the whole formula.

---

## Delivery requirements

| # | Requirement | Status | Evidence |
| --- | --- | --- | --- |
| 1 | Complete Kuikly source, business logic in the shared layer | **Yes** | Effectively all UI, protocol and model code is in `shared/commonMain`; platform code is bridges only |
| 2 | Mock replay data or a reproducible Host test environment | **Yes** | `tools/mock-host`: cookie auth, the real `/api/remote.mux` envelope, eight scripted scenarios, seed sessions, 5 e2e sub-tests |
| 3 | Demo video | **No** | Run-sheet prepared; the video itself has to be recorded |
| 4 | Short design document | **Yes** | [`design.md`](design.md) — message model, streaming state machine, attachment protocol, export format |

## Platform coverage

| Platform | Status |
| --- | --- |
| Android | Fully implemented and verified at runtime |
| iOS | Bridges written and compile-checked in Xcode only, never run. `HRDshMediaModule.h/.m` is not yet added to the Xcode target, and `databaseDir` is not forwarded to pushed pages (pre-existing) |
| OpenHarmony | Out of scope by decision; shared UI would run, native bridges are not implemented |

## Test suite

184 automated tests, all passing: **165** Kotlin unit tests in `shared/commonTest`, **14**
for the companion Host plugin, and **5** end-to-end sub-tests for the mock Host.

The Kotlin suite: host store and timeline projection (60),
protocol envelopes and parsing (13), streaming turns (11), attachment prevalidation (11),
message export including HTML (14), LaTeX (17), plugin catalogue (8), log centre (9), theme
contrast (5), solar clock (6), incremental markdown (8) and session scope (1).
