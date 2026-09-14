import { findEntry, mapEntry } from './inventory.js'
import { isProtectedModule } from './protected.js'

export const ACTIONS = ['enable', 'disable', 'reload']

/**
 * Whether `action` may run against `entry`, without running it.
 *
 * The app calls this indirectly on every request: a write without `confirm`
 * returns the preview so the phone can show a confirmation naming the module.
 * @param {any} entry Loader entry, or undefined when the id is unknown.
 * @param {string} action
 */
export function previewControl(entry, action) {
    if (!ACTIONS.includes(action)) {
        return { ok: false, code: 'unknown-action', message: `Unsupported action ${action}.` }
    }
    if (entry === undefined) {
        return { ok: false, code: 'not-found', message: 'That plugin is no longer in the inventory.' }
    }
    const view = mapEntry(entry)
    if (isProtectedModule(view.moduleName)) {
        return { ok: false, code: 'protected', message: view.protectedReason, entry: view }
    }
    if (action === 'enable' && view.enabled) {
        return { ok: false, code: 'no-op', message: 'That plugin is already enabled.', entry: view }
    }
    if (action === 'disable' && !view.enabled) {
        return { ok: false, code: 'no-op', message: 'That plugin is already disabled.', entry: view }
    }
    return { ok: true, entry: view }
}

/**
 * Runs the action through the loader's own supported writes.
 *
 * Reload is a disable/enable pair rather than a fiber poke, so the loader
 * rebuilds the entry the same way it would after a config change. Each step
 * waits for the plugin tree to settle, so the phase the caller reads back is the
 * settled one and not a transient `loading`.
 * @param {any} loader The `loader` service.
 * @param {any} entry Loader entry.
 * @param {string} action
 */
export async function applyControl(loader, entry, action) {
    if (action === 'enable') {
        await entry.update({ disabled: false })
    } else if (action === 'disable') {
        await entry.update({ disabled: true })
    } else {
        await entry.update({ disabled: true })
        await settle(loader)
        await entry.update({ disabled: false })
    }
    await settle(loader)
    return findEntry(loader, String(entry.id))
}

async function settle(loader) {
    if (typeof loader.await === 'function') await loader.await()
}
