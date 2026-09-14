# dsh-mobile-admin protocol

The wire contract between the app and the optional companion Host plugin in
[`host-plugin/`](../host-plugin/README.md). **This is not official DSH.** Everything the
app does over `/api` uses methods the Host already publishes; this route exists only for
the capability Task 5's third bonus asks for and `pluginInventory/list` does not have.

When the plugin is not installed the route 404s, the app stays on the official read-only
inventory, and no controls are shown.

## Transport

```text
POST {baseUrl}/dsh-mobile/rpc
GET  {baseUrl}/dsh-mobile/health
Cookie: dsh-auth-…=…            ← the same browser-session cookie /api requires
Content-Type: application/json
```

Authentication is the Host's own: the plugin calls `connection.requestRejection(req)`
before anything else, which yields `403` for a failed Host/Origin check and `401` for a
missing or stale session cookie. The app already holds that cookie for `/api`, so no new
credential exists and no new way in is created.

Envelope — deliberately *not* the Host's `client-request`/`server-response`, so the two are
never confused in a log:

```json
→ { "method": "plugin.control", "params": { "entryId": "llm-deepseek", "action": "disable", "confirm": true } }
← { "ok": true,  "value": { … } }
← { "ok": false, "error": { "code": "confirm-required", "message": "…", "details": { … } } }
```

HTTP status is `200` for every application-level outcome; only authentication and routing
use non-200. Bodies are capped at 64 KB.

## Methods

### `mobile.capabilities`

```json
← { "ok": true, "value": { "protocol": 1, "actions": ["enable", "disable", "reload"], "writable": true } }
```

The app calls this when the Plugins sheet opens. Capabilities are **advertised**, never
inferred by attempting a write and reading the error — an error-probe would write noise
into the Host log on every connect.

### `plugin.inventory`

```json
← { "ok": true, "value": { "protocol": 1, "writable": true, "entries": [ PluginEntry, … ] } }
```

```ts
interface PluginEntry {
  entryId: string          // matches the official pluginInventory/list entryId
  moduleName: string
  enabled: boolean         // the loader's disabled flag, inverted
  fiberPhase: 'pending' | 'loading' | 'active' | 'failed' | 'unloading' | null
  configuration: unknown   // the entry's config, secrets masked as «redacted»
  controllable: boolean    // false for protected modules
  protectedReason: string  // why, when controllable is false
}
```

`fiberPhase` uses the official phase names, including `null` for a disposed fiber, so the
app reuses the parser it already has. The app takes only `controllable` from this call and
keeps rendering the official inventory, so the two never disagree about what exists.

### `plugin.control`

```json
→ { "method": "plugin.control", "params": { "entryId": "…", "action": "enable|disable|reload", "confirm": true } }
← { "ok": true, "value": { "entryId": "…", "action": "…", "entry": PluginEntry } }
```

Two-step by design. Without `confirm: true` the plugin performs no write and answers:

```json
← { "ok": false, "error": { "code": "confirm-required",
      "message": "Confirm before you disable @deepseek-ai/dsh-llm-deepseek.",
      "details": { "entry": PluginEntry, "action": "disable" } } }
```

The app shows that message verbatim, so the dialog names the module the **Host** resolved.
On confirmation it repeats the call with `confirm: true`.

`reload` is a disable/enable pair through `entry.update({disabled})`, not a fiber poke, so
the loader rebuilds the entry the way it would after a config change. Every step waits for
the plugin tree to settle, so the phase in the reply is settled rather than a transient
`loading`.

## Error codes

| Code | Meaning |
| --- | --- |
| `unauthorized` / `forbidden` | `401` / `403` from `connection.requestRejection` |
| `not-found` | Unknown entry id, or a request to a path this plugin does not serve |
| `unknown-method` | Method not implemented; treat the plugin as absent for that feature |
| `protected` | The module carries the connection or the session state and is never controllable |
| `no-op` | Already in the requested state |
| `confirm-required` | Write refused pending confirmation; `details.entry` names the module |
| `internal` | Unexpected failure; the message is the thrown error |

## Versioning

`protocol` is `1`. The app treats an unknown higher value as "newer Host plugin, features
I do not know about" and continues with the intersection it understands; an absent or
non-`true` `writable` means read-only.
