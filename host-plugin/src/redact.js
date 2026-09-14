/**
 * Plugin configuration is shown in the app, and plugin configuration is where
 * deployments keep API keys. Values are masked by key name before they leave the
 * Host, so a secret never reaches the phone in the first place — the same rule the
 * app's own log centre follows.
 */
const SECRET_KEY = /(key|token|secret|password|passwd|credential|cookie|auth|signature|salt)/i

/** Long opaque strings are masked even under an innocent key name. */
const OPAQUE_VALUE = /^[A-Za-z0-9+/_-]{40,}={0,2}$/

/**
 * A copy of `value` with secret-looking leaves replaced by a fixed mask.
 * @param {unknown} value Configuration value of any shape.
 * @param {string} [keyName] The key this value was found under.
 * @returns {unknown}
 */
export function redact(value, keyName = '') {
    if (value === null || value === undefined) return value
    if (Array.isArray(value)) return value.map(item => redact(item, keyName))
    if (typeof value === 'object') {
        const out = {}
        for (const [key, item] of Object.entries(value)) out[key] = redact(item, key)
        return out
    }
    if (typeof value !== 'string') return value
    if (SECRET_KEY.test(keyName)) return '«redacted»'
    if (OPAQUE_VALUE.test(value)) return '«redacted»'
    return value
}
