# DSH App user guide

A walkthrough of the DSH mobile app: how to connect it to a DeepSeek Harness Host, and
how to use everything the app can do.

The app is a **client**. It does not run an agent or hold your API key — it talks to a DSH
Host running on your computer, and that Host owns the models, the tools, the plugins and
the conversation history. If the Host is off, the app has nothing to talk to.

Setup on the computer side (Relay, plugin install, `dsh web`) is in the
[README](../README.md); this guide starts from "the app is installed on my phone".

Screen labels are quoted exactly as they appear. Some are still Chinese (`新会话`,
`设置`); everything added recently is English.

---

## 1. Connecting

The first screen is **连接 DSH** with three tabs.

| Tab | Use it when | What you fill in |
| --- | --- | --- |
| **扫码连接** (QR) | Phone and computer are on the same Wi-Fi and you have the Relay + plugin running | Nothing — you scan the QR code from the computer's DSH **Settings → Remote Access** |
| **SSH** | You already have an SSH key on the computer, or you don't want to run the Relay | SSH host, port, user, private key, remote DSH port, and the login token `dsh web` prints |
| **直连** (Direct) | Development and trying the app out — the phone can open the Host's address directly | The DSH address, and optionally the login token |

**QR:** tap **扫描电脑二维码**, point at the code, then **连接已配对电脑**. The app stores one
computer; to switch, remove it first and scan a new code. The address is baked into the QR
code at generation time, so if the computer changes network you must update
`PUBLIC_RELAY_URL`, restart `dsh web` and scan a **new** code.

**SSH:** the app only sets up a local port forward — it never runs remote commands. The
first connection shows the host fingerprint for you to confirm.

**Login token:** DSH 0.1.2 and later require a browser session before any API call. `dsh web`
prints a URL like `http://127.0.0.1:3080/?token=…` on startup — paste either the whole URL
or just the token. It changes every time `dsh web` restarts; when it expires the app asks
for a new one instead of silently failing.

### Trying the app without a real Host

The repo ships a mock Host that speaks the real protocol, which is the fastest way to see
every feature:

```bash
cd tools/mock-host
npm install
npm start -- --host 0.0.0.0 --port 3080 --token dev-token
```

Then pick **直连**, enter `http://10.0.2.2:3080` on an emulator or `http://<computer LAN
IP>:3080` on a real phone, leave the token blank, and tap **直连 DSH**.

The mock picks a scripted reply from keywords in your message, so you can summon each kind
of content on demand:

| Say this | You get |
| --- | --- |
| anything | a Markdown reply with headings, a table and a code block |
| `tool` | interleaved text and tool cards, including one that fails and one file diff |
| `image` | a reply that echoes an image back |
| `approve` | a tool-approval request you have to answer |
| `ask` | a multi-question form the agent asks you |
| `fail` | a turn that ends in an error |
| `latex` | formulas (not rendered yet — see §11) |
| `long` | a long reply, for testing scrolling |

---

## 2. The chat screen

**Top bar** — the menu button (top left) opens the drawer, the middle shows the
conversation title, and the pill on the right is the connection state: `已连接` when it is
healthy, `正在生成` while a reply streams, `远程连接重建中` while reconnecting.

**Composer** (bottom) —

- the text field,
- a model chip (`DeepSeek Chat ⌄`) that opens the model picker,
- a **sliders** button that opens the image menu; it turns blue while the menu is open,
- a round button on the right: a microphone when there is nothing to send, an arrow once
  you type or attach something, and a **square** to stop while a reply is streaming.

**Replies** stream in as they are generated, block by block. Markdown is rendered live:
headings, lists, quotes, tables, links, inline code and fenced code blocks with syntax
highlighting. A block that is already finished never re-lays-out, so text does not jump
around while more arrives.

**Tool cards** appear inline, in the order the agent ran them. Each shows the tool name and
a one-line summary; tap to expand its output. Terminal output and JSON are shown as
listings, file edits as a diff, and a failed tool is tinted red. Long output collapses with
`… 其余 N 行` and a `收起` control.

**Approvals** — when the agent wants to run something that needs permission, a card asks
you before it proceeds; tool cards waiting on you read `等待审批`.

**Questions** — when the agent asks you something, a form appears with the options; use
`上一题` / `下一题` to move between questions and `跳过` to skip one.

**Queue** — messages you send while the agent is busy queue up. The queue dock shows
`队列 · N` and each item can be edited (`编辑`), removed (`删除`) or turned into a steer
(`转向`).

---

## 3. The drawer

Tap the menu button in the top left.

| Row | What it does |
| --- | --- |
| `新会话` | Start a new conversation |
| `设置` | Connection settings; also where you paste a fresh login token |
| **Appearance** | Themes (§5) |
| **Plugins** | What the Host has loaded (§9) |
| **Logs** | Diagnostics (§10) |
| **Archived** *(with a count)* | Archived conversations (§8) |
| the list below | Your conversations, grouped by workspace |

Every conversation row has a `···` button for renaming and archiving.

> One caveat: pressing the system **Back** button on the chat screen returns you to the
> connection screen, which costs a reconnect. Use the drawer to move around.

---

## 4. Sending images and photos

Tap the **sliders** button in the composer to open the image menu, then choose the photo
library or the camera.

- Pick **one or several** images; they appear as thumbnails above the composer. Each has an
  ✕ to remove it, and the caption shows the dimensions and size.
- Send them on their own or with text. The composer shows a sending state until the Host
  has accepted and stored the images — not just until the first word comes back.
- **PNG, JPEG, WebP and GIF** are accepted. Anything else, or anything past the Host's
  limits on count, bytes or pixel size, is rejected **before** sending, with the reason
  written out ("This image is larger than the 20 MB the Host accepts."). The image stays
  staged so you can remove it or pick another; your typed message is never lost.
- Taking a photo needs camera permission. If you deny it, the app says so and points you at
  Settings rather than failing silently.
- Sent images appear as thumbnails in your message; tap one for a full-screen view with its
  name, dimensions and size. Reopening the conversation later fetches them from the Host
  again, so they survive an app restart.

---

## 5. Themes

**Drawer → Appearance.**

- **Theme:** Light, Dark, System (follows the device), or High contrast (an accessible dark
  palette with stronger contrast). The change applies instantly across every surface —
  conversation, composer, Markdown, code blocks, tool cards, dialogs — and survives a
  restart. In System mode, changing the device's dark-mode setting flips the app live
  without losing your place.
- **Tool output:** Auto, Light or Dark. Terminal output, diffs and JSON listings can keep
  their own light or dark theme independently of the app's. Markdown code blocks follow the
  app theme — the Markdown renderer paints inline code and fenced code with one shared
  colour, so those cannot be themed separately.

---

## 6. Selecting, copying and exporting

**Long-press any message** — a bubble, a tool card, anything — to open its action sheet.

| Action | What you get |
| --- | --- |
| **Select text** | Selection mode with drag handles. A floating bar offers **Copy**, **Select all** and **Done** |
| **Copy message** | The whole message as readable text, with no interface labels |
| **Copy code block N** | Just that block's code, with no fences and no surrounding prose |
| **Export conversation** | The whole conversation as Markdown, through the system share sheet |

Copying a message keeps the original order of prose, tool cards and attachments. A tool card
copies as a heading with its name, summary and status (`## Tool · Grep — TODO (failed)`)
followed by its output; an image copies as a readable line with its name, dimensions, size
and attachment id. Copying mid-reply gives you exactly what is on screen.

---

## 7. Choosing a model

Tap the model chip in the composer. The list comes from the Host, and the choice applies to
the current conversation. If the Host reports a provider failure, the picker says so.

---

## 8. Renaming and archiving conversations

Tap `···` on a conversation row in the drawer.

**Rename** — type a new title and save. The Host owns titles, so if it rejects one (an empty
title, for instance) you see its reason and the field is marked. The new name is what you
will see after restarting the app, because it was stored on the Host, not locally.

**Archive** — asks you to confirm first, and says plainly what it does: archiving **hides**
the conversation from the main list. It is not deleted. The history, the logs and the
workspace entry all stay on the Host, and you can open it again from **Archived**. If you
archive the conversation you are currently reading, the app moves you to the most recent
conversation still in the main list, or to a fresh blank one.

**Archived** in the drawer lists them with a live count. Opening one shows its complete
history exactly as before.

There is no delete, and no restore-from-archive. Both would need Host operations that do not
exist yet, and the app does not fake them by hiding things locally.

---

## 9. Plugins

**Drawer → Plugins** shows what the Host's plugin loader currently has, and their status.

- **Search** by module name or entry id.
- **Filter** by status. The chips carry counts and only appear for statuses actually
  present: Failed to start, Running, Loading, Unloading, Waiting for dependencies, Not
  running, Disabled.
- **Tap a row** to expand it into the Host's full configuration: the complete module name,
  the configuration state, and the lifecycle status.
- **A failed plugin** is listed first and outlined in red. Its detail names the module and
  entry the Host could not start. The Host's plugin inventory reports only the lifecycle
  phase and carries no failure message, so the app says so rather than inventing one — the
  cause is in the Host's own log.
- **Refresh** re-reads the list. If the read fails you get the reason and a **Retry**.

This list is **read-only**, which is why there are no enable/disable switches: the Host
exposes no operation to call. Anything that could not work is not offered.

---

## 10. Logs and issue reports

**Drawer → Logs** is a diagnostic feed of what the app observed: connection events, Host
RPCs, stream frames and conversation events. It is useful when a reply stalls, a card looks
out of order, or the connection keeps dropping.

Each record carries a time, a level, an event type, and — for anything conversation-related
— the conversation id and the frame's sequence number or request id.

Filter with any combination of:

- **Search** across the summary, conversation id and reference,
- **Event type** chips with counts (`assistant/chunk`, `tool/call`, `tool/result`,
  `turn/end`, `connection`, `rpc`, `reconnect`, …),
- **Minimum level** — Debug, Info, Warn, Error. Set it to Error to see only what failed,
- **This conversation** to scope to what you are reading,
- **All / 5 min / 1 h** time windows.

**Tap a record** for its full detail; **Copy** puts exactly that text on the clipboard.

**Export** opens the share sheet with an issue report: connection mode, connection state,
platform, app version, the filter you had applied, and the matching records oldest-first.

Three things worth knowing:

- **Nothing sensitive is recorded.** Tokens, cookies, `Authorization` headers, API keys and
  long base64 blobs are stripped when the record is written, so the list, the detail view,
  the clipboard and the export are all redacted — there is no path that leaks.
- **No message text.** Streamed replies are recorded as metadata only: which conversation,
  which chunk type, how many characters. Your conversation content is not in the log.
- **Logs are local and bounded.** The newest 1000 records are kept in memory for the current
  run of the app. **Clear** empties that buffer and nothing else — your conversations and the
  Host's history are untouched.

The list is a snapshot taken when you open the sheet; tap **Refresh** for the latest. That
is deliberate — updating it on every frame would slow down the conversation while a reply is
streaming.

---

## 11. Not there yet

- **Formulas.** `$…$` and `$$…$$` are not rendered; they currently disappear from replies.
- **PDFs and other files.** Only images are supported. The official attachment protocol
  covers images only, and the app will not claim more than the Host can actually store.
- **Deleting conversations, restoring from archive, enabling plugins.** All three need Host
  operations that do not exist in this version.
- **PDF/HTML export** and selecting several messages to export together.

---

## 12. If something goes wrong

| Symptom | Try this |
| --- | --- |
| QR scan never connects | Same network? Is the QR code's address the computer's *current* IP? Did you restart `dsh web` and rescan after changing it? Is the Relay still listening on 8787? |
| Asks for a token again | `dsh web` restarted and printed a new one. Drawer → `设置` and paste it |
| `远程连接重建中` won't clear | The Host or the tunnel went away. Check the computer, then Drawer → Logs and set the level to Error |
| Replies never start | Check the model picker, and that the API key is configured **on the computer**, not the phone |
| An image is refused | Read the reason under the thumbnail; it names the limit that was exceeded |
| Anything else | Drawer → Logs, filter to Error, then **Export** and attach that to your report |
