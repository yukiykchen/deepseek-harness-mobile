/**
 * Modules the phone may never stop.
 *
 * Two kinds live here. The first is anything the phone is talking *through* —
 * stopping the web server or the connection service would sever the very request
 * doing the stopping and leave the Host unreachable without a restart. The second
 * is anything session state depends on; a mobile client has no business unloading
 * storage or session persistence underneath a running agent.
 *
 * Matching is by substring on the module specifier, so scoped and unscoped builds
 * of the same package are both covered.
 */
const PROTECTED_FRAGMENTS = [
    // This plugin, and the pairing plugin that carries the phone's traffic.
    'dsh-mobile-admin',
    'dsh-scan-remote',
    // The request path itself: the server, the auth fence, the RPC gateway and
    // every controller the app's own endpoints are served by.
    'webserver',
    'web-startup',
    'frontend-static',
    'client-connection',
    'gateway',
    '-controller',
    // The loader would be unloading itself.
    'plugin-loader',
    'loader',
    // State the running agents depend on.
    'storage',
    'session-persistence',
    'workspace',
    // Read-only inventory the app falls back to when this plugin is absent.
    'plugin-inventory',
]

/**
 * Whether `moduleName` must not be controlled from the phone.
 * @param {string} moduleName Module specifier from the loader entry.
 * @returns {boolean}
 */
export function isProtectedModule(moduleName) {
    const name = String(moduleName ?? '').toLowerCase()
    if (name.length === 0) return true
    return PROTECTED_FRAGMENTS.some(fragment => name.includes(fragment))
}

export { PROTECTED_FRAGMENTS }
