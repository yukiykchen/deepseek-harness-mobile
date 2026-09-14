# Demo video run-sheet

Delivery requirement 3 names seven things the video must cover: status prompts, interleaved
cards, formulas, attachments, copy and export, retrying abnormal failures, and switching
between conversations. Each has a scene below, in an order that needs no backtracking.
Roughly 6–8 minutes at a normal pace.

Everything runs against `tools/mock-host`, so the demo is reproducible and needs no API key.

## Before recording

```bash
# 1. Mock Host (Node 22; /opt/homebrew/bin/node is a broken 16)
export PATH="/opt/homebrew/opt/node@22/bin:$PATH"
cd tools/mock-host && npm install
node src/server.js --host 0.0.0.0 --port 3080 --token dev-token --verbose

# 2. Emulator
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
cd $ANDROID_HOME/emulator && ./emulator -avd dsh34 -no-snapshot -no-boot-anim -gpu host -no-audio &
adb wait-for-device && adb shell dumpsys deviceidle disable && adb shell svc power stayon true

# 3. App
export JAVA_HOME="$HOME/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Restart the mock Host immediately before recording so the session list is at its seed state:
`seed-kotlin-coroutines` plus one archived `seed-archived-notes`.

Put three test images on the device — a normal one, a second normal one, and one large enough
to breach `maxImageBytes` (a 3000×3000 incompressible-noise PNG is about 27 MB):

```bash
adb push /tmp/dsh-ok.png /tmp/dsh-ok2.png /tmp/dsh-huge.png /sdcard/Pictures/
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/dsh-ok.png
```

Grant the camera up front if scene 4 will use it: `adb shell pm grant com.example.dsh android.permission.CAMERA`.

The mock picks a scenario from keywords in the prompt: default markdown, and `latex`, `tool`,
`image`, `fail`, `approve`, `ask`, `long`.

## Scenes

### 1 · Connect (≈30 s)

Open the app on **连接 DSH**, show the three connection modes, choose **直连**, and connect to
`http://10.0.2.2:3080`. Say out loud that scan-relay and SSH are the real paths and Direct is
the development one pointed at the mock Host.

*Covers:* nothing required, but it establishes that the Host is real and remote.

### 2 · Status prompts and streaming (≈60 s) — **required clause 1**

Send `hello there`. Point at, in order: the turn-status line appearing on the receipt rather
than on the first token, reasoning text streaming before the answer, the answer rendering
progressively block by block, and settled blocks not re-flowing as later ones arrive. Let it
finish so the status line clears.

### 3 · Interleaved cards (≈75 s) — **required clause 2**

Send `tool please`. Show prose and tool cards interleaving in true order, a card expanding to
its terminal output, the JSON listing, the file **diff** view, and the failed card in red.

Then send `approve this` and answer the approval waterfall; then `ask me` and answer the
two-question waterfall, showing that paging keeps each question's draft.

### 4 · Attachments (≈90 s) — **required clause 4**

1. Open the attachment menu, pick **two** images at once, and show the staged strip with
   dimensions and byte size.
2. Send with the text `image please`. Show the thumbnails in the user bubble, and the
   assistant's echoed image between the reply bubbles.
3. Tap a thumbnail for the full-screen viewer (name, `W×H`, size).
4. Take a **photo** and send it, showing it goes through the same pipeline.
5. Try to attach the 27 MB PNG. Show that it is rejected **before** anything is sent, with the
   Host's own reason wording, that send stays blocked, and that removing it clears the notice.
6. Say that the conversation log keeps only an `attachmentId` — no base64, no local path.

Note for the narration: against a local mock the upload states are sub-second. If you want
"uploading" visible on camera, start the mock with `--speed slow` or use a much larger image.

### 5 · Formulas (≈60 s) — **required clause 3**

Send `latex please`. Show the inline `mc²`, the three block formulas each on their own row,
the matrix over two rows, then scroll to the two fallbacks: the unclosed formula that stays
plain text and the `$dollar$` inside a code span that is left alone.

Long-press the reply and use **Copy formula 3**, paste it into the composer to show the
clipboard holds `x^2 + y^2 = z^2` — the LaTeX source, not the rendered glyphs. Clear the
composer afterwards.

### 6 · Copy and export (≈90 s) — **required clause 5**

On the same reply:

1. **Select text** — drag the native handles, Copy.
2. **Copy message** — paste into the composer to show prose plus fenced code, and clear it.
3. **Copy code block 1** — paste to show code only, no fence.
4. **Select messages** — pick two rows, Export, show the Markdown transcript in the share sheet.
5. **Export as HTML** — show the `.html` file in the chooser.
6. **Print / save as PDF** — show the rendered print preview and the **Save as PDF**
   destination, and actually save one.

### 7 · Retrying abnormal failures (≈90 s) — **required clause 6**

1. Send `fail now`. Show the error bubble and that the partial reply before the failure is
   **kept**, matching the Host's own log.
2. Kill the mock Host (`PID=$(lsof -nP -iTCP:3080 -sTCP:LISTEN -t); kill -9 "$PID"`). Show the
   reconnect banner, then try to send with two images staged — the send is refused and the
   images are preserved.
3. Restart the mock. Show the cookie re-mint, the streams re-opening, the session list
   re-reading, history intact, and the same two images sending successfully.

### 8 · Switching between conversations (≈60 s) — **required clause 7**

Open the drawer and switch between the conversations created above, showing each one's history
loads complete and the streaming state belongs to the right conversation. Rename one through
the `···` menu — first with an empty title to show the Host's `title-invalid` rejection, then
with a real one. Archive a conversation, showing the confirmation copy that says it hides
rather than deletes, the automatic switch away from it, and the **Archived** list with its
count where it can be reopened with full history.

### 9 · Themes, plugins, logs (≈75 s) — not required, but cheap and it is where the marks are

- **Appearance**: Light → Dark → System (flip the device with
  `adb shell cmd uimode night yes`) → Sunrise to sunset → High contrast, on the `latex`
  conversation so the formula blocks and code repaint on camera. Show Tool output set to an
  independent code theme.
- **Plugins**: the populated inventory with the failed entry first, search, a status filter,
  an expanded configuration, and the note explaining why there are no enable/disable controls.
- **Logs**: the populated list, the Error filter finding the failed turn from scene 7,
  `assistant/chunk` records showing metadata only, a record detail, and the redacted export
  bundle in the share sheet. Point out that no token or base64 appears anywhere.

## Tap coordinates (1080 × 2400)

Useful if you drive any of it from the shell rather than by hand.

| Target | Coordinates |
| --- | --- |
| Direct-mode connect | `539 2209` |
| Drawer hamburger | `80 204` |
| Drawer: 设置 / Appearance / Plugins / Logs / Archived | `206 487` / `206 607` / `206 729` / `206 849` / `206 970` |
| Composer | `539 2069` |
| Send (keyboard up / hidden) | `977 1389` / `977 2209` |
| Hide-keyboard chevron | `162 2334` |
| Bottom-sheet ✕ | `989 539` |

Paste the clipboard back into the composer with `adb shell input keyevent 279`; clear it with
`adb shell input keycombination 113 29` then `adb shell input keyevent 67`.

## Pitfalls

- **Wait for the setup page before the first tap.** Taps landing on the splash corrupt the
  persisted Direct base URL. After `adb install -r` the first launch can take 20 s (dexopt);
  poll `adb logcat -d | grep connection_setup` rather than sleeping a fixed amount.
- **Never kill the mock Host by port.** `lsof -ti tcp:3080 | xargs kill -9` kills the
  *emulator*, because qemu holds sockets on that port for the guest. Kill only the listener.
- **Boot the emulator with `-gpu host`.** With swiftshader the app ANRs seconds after the
  first keystroke with a stuck-fence GPU hang, which looks exactly like an app deadlock.
- **Disable doze** (`dumpsys deviceidle disable`, `svc power stayon true`) or the mux socket
  dies after about a minute of idle and the reconnect noise is not a real defect.
