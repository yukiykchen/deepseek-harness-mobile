import assert from 'node:assert/strict'
import test from 'node:test'

import { previewControl } from '../src/control.js'
import { collectEntries, mapEntry } from '../src/inventory.js'
import { isProtectedModule } from '../src/protected.js'
import { redact } from '../src/redact.js'
import { createHandler, dispatch } from '../src/index.js'

/** Minimal stand-in for a Cordis loader entry. */
function entry(id, name, { disabled = false, state = 2, config = {}, group = false } = {}) {
    const record = {
        id,
        disabled,
        options: { name, config, group },
        fiber: state === null ? null : { state },
        async update(patch) {
            if (Object.hasOwn(patch, 'disabled')) {
                record.disabled = patch.disabled
                record.fiber = patch.disabled ? null : { state: 2 }
            }
        },
    }
    return record
}

function loaderOf(...entries) {
    return { entries: () => entries, await: async () => {} }
}

// --- inventory -------------------------------------------------------------

test('maps a loader entry to the official phase names plus configuration', () => {
    const view = mapEntry(entry('e1', 'demo-plugin', { config: { mode: 'fast' } }))
    assert.equal(view.entryId, 'e1')
    assert.equal(view.moduleName, 'demo-plugin')
    assert.equal(view.enabled, true)
    assert.equal(view.fiberPhase, 'active')
    assert.deepEqual(view.configuration, { mode: 'fast' })
    assert.equal(view.controllable, true)
})

test('a disposed fiber reports no phase, matching the official inventory', () => {
    assert.equal(mapEntry(entry('e1', 'demo', { state: 4 })).fiberPhase, null)
    assert.equal(mapEntry(entry('e2', 'demo', { state: 3 })).fiberPhase, 'failed')
})

test('group entries are containers, not plugins', () => {
    const entries = collectEntries(loaderOf(entry('g', 'group', { group: true }), entry('e', 'demo')))
    assert.deepEqual(entries.map(e => e.entryId), ['e'])
})

// --- safety ----------------------------------------------------------------

test('modules carrying the connection or session state are protected', () => {
    for (const name of [
        '@deepseek-ai/dsh-host-webserver',
        '@deepseek-ai/dsh-client-connection',
        '@deepseek-ai/dsh-api-session-controller',
        '@deepseek-ai/dsh-web-startup',
        '@deepseek-ai/cordis-plugin-loader',
        '@deepseek-ai/dsh-storage-json',
        '@deepseek-ai/dsh-workspace',
        'dsh-scan-remote',
        'dsh-mobile-admin',
    ]) {
        assert.equal(isProtectedModule(name), true, `${name} must be protected`)
    }
    assert.equal(isProtectedModule('@deepseek-ai/dsh-host-open-in-app'), false)
    assert.equal(isProtectedModule(''), true, 'an unnamed module is protected by default')
})

test('configuration secrets are masked before they leave the Host', () => {
    const masked = redact({
        endpoint: 'https://api.example.com',
        apiKey: 'sk-1234567890',
        nested: { authToken: 'abc', note: 'keep me' },
        opaque: 'A'.repeat(64),
        list: [{ password: 'hunter2' }],
    })
    assert.equal(masked.endpoint, 'https://api.example.com')
    assert.equal(masked.apiKey, '«redacted»')
    assert.equal(masked.nested.authToken, '«redacted»')
    assert.equal(masked.nested.note, 'keep me')
    assert.equal(masked.opaque, '«redacted»')
    assert.equal(masked.list[0].password, '«redacted»')
})

test('a protected module cannot be controlled and an unknown action is refused', () => {
    assert.equal(previewControl(entry('e', '@deepseek-ai/dsh-host-webserver'), 'disable').code, 'protected')
    assert.equal(previewControl(entry('e', 'demo'), 'explode').code, 'unknown-action')
    assert.equal(previewControl(undefined, 'disable').code, 'not-found')
    assert.equal(previewControl(entry('e', 'demo', { disabled: true }), 'disable').code, 'no-op')
})

// --- dispatch --------------------------------------------------------------

test('a write without confirm returns the preview instead of acting', async () => {
    const target = entry('e', 'demo')
    const result = await dispatch(loaderOf(target), 'plugin.control', { entryId: 'e', action: 'disable' })
    assert.equal(result.ok, false)
    assert.equal(result.error.code, 'confirm-required')
    assert.equal(result.error.details.entry.moduleName, 'demo')
    assert.equal(target.disabled, false, 'nothing may change before confirmation')
})

test('a confirmed disable and enable round-trips through the loader', async () => {
    const target = entry('e', 'demo')
    const loader = loaderOf(target)
    const off = await dispatch(loader, 'plugin.control', { entryId: 'e', action: 'disable', confirm: true })
    assert.equal(off.ok, true)
    assert.equal(off.value.entry.enabled, false)
    assert.equal(off.value.entry.fiberPhase, null)

    const on = await dispatch(loader, 'plugin.control', { entryId: 'e', action: 'enable', confirm: true })
    assert.equal(on.ok, true)
    assert.equal(on.value.entry.enabled, true)
    assert.equal(on.value.entry.fiberPhase, 'active')
})

test('reload leaves the entry enabled', async () => {
    const target = entry('e', 'demo')
    const result = await dispatch(loaderOf(target), 'plugin.control', { entryId: 'e', action: 'reload', confirm: true })
    assert.equal(result.ok, true)
    assert.equal(result.value.entry.enabled, true)
    assert.equal(target.disabled, false)
})

test('capabilities advertise the protocol rather than making the app probe by error', async () => {
    const result = await dispatch(loaderOf(), 'mobile.capabilities', {})
    assert.equal(result.ok, true)
    assert.equal(result.value.protocol, 1)
    assert.deepEqual(result.value.actions, ['enable', 'disable', 'reload'])
})

test('an unknown method is refused', async () => {
    const result = await dispatch(loaderOf(), 'plugin.install', {})
    assert.equal(result.error.code, 'unknown-method')
})

// --- authentication --------------------------------------------------------

function responseSpy() {
    return {
        statusCode: 0,
        body: '',
        writeHead(status) { this.statusCode = status },
        end(body) { this.body = body ?? '' },
    }
}

function request(method, url, body = undefined) {
    const chunks = body === undefined ? [] : [Buffer.from(JSON.stringify(body))]
    return {
        method,
        url,
        async *[Symbol.asyncIterator]() { yield* chunks },
    }
}

test('an unauthenticated request never reaches the loader', async () => {
    let touched = false
    const loader = { entries: () => { touched = true; return [] }, await: async () => {} }
    const handler = createHandler(loader, () => ({ requestRejection: () => 401 }))
    const res = responseSpy()
    await handler(request('POST', '/dsh-mobile/rpc', { method: 'plugin.inventory' }), res)
    assert.equal(res.statusCode, 401)
    assert.equal(touched, false, 'the loader must not be read before authentication')
})

test('a cross-origin request is refused with 403', async () => {
    const handler = createHandler(loaderOf(), () => ({ requestRejection: () => 403 }))
    const res = responseSpy()
    await handler(request('GET', '/dsh-mobile/health'), res)
    assert.equal(res.statusCode, 403)
})

test('an authenticated inventory request succeeds', async () => {
    const handler = createHandler(loaderOf(entry('e', 'demo')), () => ({ requestRejection: () => undefined }))
    const res = responseSpy()
    await handler(request('POST', '/dsh-mobile/rpc', { method: 'plugin.inventory' }), res)
    assert.equal(res.statusCode, 200)
    const parsed = JSON.parse(res.body)
    assert.equal(parsed.ok, true)
    assert.equal(parsed.value.entries[0].moduleName, 'demo')
})
