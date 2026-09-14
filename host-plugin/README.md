# dsh-mobile-admin

An **optional** companion plugin for the DSH Host, installed alongside it the same way
`dsh-scan-remote` is. It does not patch DeepSeek Harness.

The official `pluginInventory/list` is read-only and reports only
`{entryId, moduleName, enabled, fiberPhase}`. This plugin adds what the mobile app needs
to satisfy Task 5's third bonus: the plugin's configuration, and the ability to enable,
disable or reload one safely.

Without it the app still shows the official inventory, just with no configuration and no
controls. Nothing degrades.

## Install

```bash
cd host-plugin
npx @deepseek-ai/dsh plugin --profile web add "link:$(pwd)"
# restart: npx @deepseek-ai/dsh web
```

Check it is up (needs the same session cookie as `/api`):

```bash
curl -i http://127.0.0.1:3080/dsh-mobile/health      # 401 without a cookie — that is correct
```

In the app, open **Plugins**: the header changes from "read-only" to naming this plugin,
and a controllable entry gains Enable / Disable / Reload when you expand it.

## Security

A route registered on `webServer` is **not** authenticated by the web server. First-party
DSH plugins gate themselves — `packages/host/open-in-app` calls
`ctx.connection.requestRejection(req)` at the top of every handler — and so does this one.
That single call applies both of the Host's own checks:

| Check | Result |
| --- | --- |
| Host/Origin fence (defeats DNS rebinding and cross-site calls) | `403` |
| Browser-session cookie, the one `GET /?token=…` mints | `401` |

So this route is exactly as hard to reach as `/api`. **Loopback is not treated as
authentication.** The phone reaches loopback through the pairing tunnel, and so does every
other process on the machine; a loopback-only check would be an unauthenticated back door
for anything running locally.

Beyond authentication:

- **A protected module list.** Anything carrying the request path (web server, connection,
  gateway, the API controllers, the static frontend) or the session state (storage, session
  persistence, workspace), plus the loader, `dsh-scan-remote` and this plugin itself, is
  never controllable. `src/protected.js` is the list; matching is by substring, so scoped
  and unscoped builds are both covered.
- **Writes require `confirm: true`.** An unconfirmed write returns `confirm-required` with
  the module name the Host resolved, which is the text the phone's dialog shows — the
  confirmation names what the *Host* will act on, not what the phone guessed.
- **Configuration is redacted on the way out** (`src/redact.js`), by key name and by
  opaque-looking value, so an API key in a plugin's config never reaches the phone.
- **No install and no uninstall.** Only lifecycle transitions on entries that already exist.
- Bodies are capped at 64 KB.

## What it deliberately does not do

Task 4's bonuses ask for session **unarchive** and **permanent delete** as Host extensions.
Neither is offered here, and neither is faked in the app:

- **Unarchive.** `WorkspaceRegistry` exposes `archiveSession` and an `archivedSessionIds`
  getter, and nothing that removes an id. Upstream states the limit outright in
  `packages/workspace/workspace/README.md`: *"Archiving is one-way — a hidden session keeps
  its history and its place, but no unarchive action exists yet."* The set lives in
  `$DSH_HOME/storages/workspace.json`, but the registry holds it in memory and every write
  is `{...this.state, archivedSessionIds: [...]}`. A plugin writing that file directly would
  be invisible to the running Host until a restart, and the next legitimate registry write
  would silently discard it. Doing it anyway would be a data-loss bug wearing a feature's
  clothes.
- **Permanent delete.** There is no delete at any layer — `SessionPersistence` has
  `create`, `open`, `stat`, `list` and `flush` and no removal. A purge would have to touch
  the JSONL log and its write lock, workspace membership, the archive set, the projection
  cache, and content-addressed attachments that are **shared by hash** between sessions.

Both become straightforward the moment upstream adds the operations, and the app's plugin
route is already the place to put them.

## Protocol

See [`docs/dsh-mobile-admin-protocol.md`](../docs/dsh-mobile-admin-protocol.md).

## Tests

```bash
npm test     # 14 tests: inventory mapping, protection, redaction, confirm gate, auth
```

The mock Host at `tools/mock-host` serves this plugin's route by calling this plugin's own
`dispatch`, with only the Cordis loader faked, so the app is exercised against the real
implementation.
