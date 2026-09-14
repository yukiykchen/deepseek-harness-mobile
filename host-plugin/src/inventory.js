import { isProtectedModule } from './protected.js'
import { redact } from './redact.js'

/** Protocol version the app checks before enabling any control. */
export const PROTOCOL = 1

/**
 * Cordis fiber states, mapped to the same phase names the official
 * `pluginInventory/list` reports so the app can reuse its existing parser.
 * `DISPOSED` deliberately has no phase: the official inventory reports null for
 * it, meaning "no live root fiber".
 */
const FIBER_PHASE = ['pending', 'loading', 'active', 'failed', null, 'unloading']

/**
 * One inventory row, in the same shape as the official entry plus the fields
 * this plugin exists to add: configuration and controllability.
 * @param {any} entry Loader entry.
 */
export function mapEntry(entry) {
    const moduleName = String(entry?.options?.name ?? '')
    const protectedEntry = isProtectedModule(moduleName)
    return {
        entryId: String(entry?.id ?? ''),
        moduleName,
        enabled: entry?.disabled !== true,
        fiberPhase: entry?.fiber === undefined || entry?.fiber === null
            ? null
            : (FIBER_PHASE[entry.fiber.state] ?? null),
        configuration: redact(entry?.options?.config ?? {}),
        controllable: !protectedEntry,
        protectedReason: protectedEntry
            ? 'This module carries the connection or the session state the Host needs to stay usable.'
            : '',
    }
}

/**
 * Every non-group entry the loader knows, in loader order.
 * @param {any} loader The `loader` service.
 */
export function collectEntries(loader) {
    const entries = []
    for (const entry of loader.entries()) {
        // Group entries are containers in the config tree, not plugins.
        if (entry?.options?.group) continue
        entries.push(mapEntry(entry))
    }
    return entries
}

/**
 * The loader entry with this id, or undefined.
 * @param {any} loader The `loader` service.
 * @param {string} entryId
 */
export function findEntry(loader, entryId) {
    for (const entry of loader.entries()) {
        if (String(entry?.id ?? '') === entryId) return entry
    }
    return undefined
}
