import { ACTIONS, applyControl, previewControl } from './control.js'
import { collectEntries, findEntry, mapEntry, PROTOCOL } from './inventory.js'
import { err, json, ok, readJson } from './rpc.js'

/**
 * dsh-mobile-admin — a companion Host plugin for the DSH mobile app.
 *
 * The official `pluginInventory/list` is read-only and carries neither the
 * configuration nor a failure cause. This plugin adds an authenticated route
 * that reports configuration and performs the lifecycle writes the app needs,
 * using only the loader's own supported operations. It does not patch DSH.
 *
 * Security. A route registered on `webServer` is NOT authenticated by the web
 * server — first-party plugins gate themselves, and so does this one. Every
 * request goes through `connection.requestRejection`, which applies the
 * Host/Origin fence (403) and then the browser-session cookie check (401), so
 * this route is exactly as hard to reach as `/api` itself. Binding to loopback
 * is not treated as authentication: the phone reaches loopback through the
 * pairing tunnel, and so does every other local process.
 *
 * Scope. Enable, disable and reload only. No install, no uninstall, and no
 * session operations — see README for why unarchive and delete are absent.
 */
export const name = 'dsh-mobile-admin'
export const inject = ['loader', 'webServer', 'connection']

const PREFIX = '/dsh-mobile'

export function apply(ctx) {
    const { loader, webServer } = ctx
    if (webServer == null || typeof webServer.register !== 'function') {
        ctx.logger?.warn?.('dsh-mobile-admin: no webServer service, routes not registered')
        return
    }
    const handler = createHandler(loader, () => ctx.connection, ctx.logger)
    ctx.effect(
        () => webServer.register({ kind: 'prefix', path: PREFIX, handler }),
        `dsh-mobile-admin: ${PREFIX}`,
    )
    ctx.logger?.info?.(`dsh-mobile-admin: POST ${PREFIX}/rpc`)
}

/**
 * @param {any} loader The `loader` service.
 * @param {() => any} connectionOf Resolves the `connection` service lazily, so a
 *   composition that reloads it does not leave a stale reference behind.
 */
export function createHandler(loader, connectionOf, logger) {
    return async (req, res) => {
        const path = new URL(req.url ?? '/', 'http://127.0.0.1').pathname
        try {
            const rejection = connectionOf()?.requestRejection(req)
            if (rejection !== undefined) {
                return json(res, rejection, err(
                    rejection === 401 ? 'unauthorized' : 'forbidden',
                    rejection === 401
                        ? 'Sign in to the Host first; this route needs the same session cookie as /api.'
                        : 'This origin is not allowed to reach the Host.',
                ))
            }
            if (req.method === 'GET' && path === `${PREFIX}/health`) {
                return json(res, 200, ok({ protocol: PROTOCOL }))
            }
            if (req.method !== 'POST' || path !== `${PREFIX}/rpc`) {
                return json(res, 404, err('not-found', `No route for ${req.method} ${path}.`))
            }
            const body = await readJson(req)
            const method = String(body.method ?? '')
            const params = body.params !== null && typeof body.params === 'object' ? body.params : {}
            return json(res, 200, await dispatch(loader, method, params))
        } catch (error) {
            logger?.warn?.(error)
            return json(res, 200, err('internal', error instanceof Error ? error.message : String(error)))
        }
    }
}

export async function dispatch(loader, method, params) {
    switch (method) {
        case 'mobile.capabilities':
            return ok({ protocol: PROTOCOL, actions: ACTIONS, writable: true })
        case 'plugin.inventory':
            return ok({ protocol: PROTOCOL, writable: true, entries: collectEntries(loader) })
        case 'plugin.control':
            return control(loader, params)
        default:
            return err('unknown-method', `This Host plugin does not implement ${method || '(empty)'}.`)
    }
}

async function control(loader, params) {
    const entryId = String(params.entryId ?? '')
    const action = String(params.action ?? '')
    const preview = previewControl(findEntry(loader, entryId), action)
    if (!preview.ok) return err(preview.code, preview.message, preview.entry ? { entry: preview.entry } : undefined)
    if (params.confirm !== true) {
        // The phone shows a confirmation naming the module, then repeats the call.
        return err('confirm-required', `Confirm before you ${action} ${preview.entry.moduleName}.`, {
            entry: preview.entry,
            action,
        })
    }
    const settled = await applyControl(loader, findEntry(loader, entryId), action)
    return ok({ entryId, action, entry: settled ? mapEntry(settled) : preview.entry })
}
