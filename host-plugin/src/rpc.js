/** Largest request body accepted, so a stray POST cannot grow the heap. */
const MAX_BODY_BYTES = 64 * 1024

export function json(res, status, value) {
    const body = JSON.stringify(value)
    res.writeHead(status, {
        'content-type': 'application/json; charset=utf-8',
        'content-length': Buffer.byteLength(body),
        'cache-control': 'no-store',
    })
    res.end(body)
}

export function ok(value) {
    return { ok: true, value }
}

export function err(code, message, details = undefined) {
    return details === undefined ? { ok: false, error: { code, message } } : { ok: false, error: { code, message, details } }
}

/**
 * Reads and parses a JSON body.
 * @throws when the body is too large or is not JSON.
 */
export async function readJson(req) {
    const chunks = []
    let size = 0
    for await (const chunk of req) {
        size += chunk.length
        if (size > MAX_BODY_BYTES) throw new Error('request body too large')
        chunks.push(chunk)
    }
    if (size === 0) return {}
    const parsed = JSON.parse(Buffer.concat(chunks).toString('utf8'))
    return parsed !== null && typeof parsed === 'object' ? parsed : {}
}
