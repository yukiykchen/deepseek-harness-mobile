package io.github.yukiykchen.dsh.feature.connection

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.reactive.handler.observable
import io.github.yukiykchen.dsh.framework.bridgeModule
import io.github.yukiykchen.dsh.framework.setTimeout
import io.github.yukiykchen.dsh.platform.DshSshConfig
import io.github.yukiykchen.dsh.model.DshRemoteProfile

/**
 * Editable SSH profile shared by the connection setup page and the in-chat
 * connection settings modal, so both validate and persist the same way.
 */

class DshSshProfileForm(
    scope: PagerScope,
    private val pickFile: ((String) -> Unit) -> Unit = { scope.bridgeModule.pickSshKey(it) },
    private val importFile: (String, (String) -> Unit) -> Unit = { uri, done -> scope.bridgeModule.importSshKey(uri, done) },
    private val deleteKey: (String) -> Unit = { scope.bridgeModule.deleteSshKey(it) },
    private val post: (() -> Unit) -> Unit = { scope.setTimeout(0, it) },
) : PagerScope by scope {
    private var hostValue by observable("")
    private var userValue by observable("")
    private var sshPortValue by observable(DEFAULT_SSH_PORT.toString())
    private var dshPortValue by observable(DEFAULT_DSH_PORT.toString())
    private var passphraseValue by observable("")
    var revision = 0L
        private set
    var closed = false
        private set
    private var onEdit: () -> Unit = {}
    var host: String
        get() = hostValue
        set(value) { if (!closed && hostValue != value) { hostValue = value; fingerprint = ""; edited() } }
    var user: String
        get() = userValue
        set(value) { if (!closed && userValue != value) { userValue = value; edited() } }
    var sshPort: String
        get() = sshPortValue
        set(value) { if (!closed && sshPortValue != value) { sshPortValue = value; fingerprint = ""; edited() } }
    var dshPort: String
        get() = dshPortValue
        set(value) { if (!closed && dshPortValue != value) { dshPortValue = value; edited() } }
    var keyId by observable("")
        private set
    var fingerprint by observable("")
    var keyLabel by observable(NO_KEY_LABEL)
        private set
    var passphrase: String
        get() = passphraseValue
        set(value) { if (!closed && passphraseValue != value) { passphraseValue = value; edited() } }
    var busy by observable(false)
    var error by observable("")
    private class Selection {
        var importing = false
        var completed = false
        var delivered = false
        var deliveredKey = ""
        var acceptedKey = ""
        val discarded = mutableSetOf<String>()
    }
    private var selection: Selection? = null
    private val unsavedKeys = mutableSetOf<String>()

    internal fun onEdited(callback: () -> Unit) { onEdit = callback }
    private fun edited() { revision++; onEdit() }

    fun load(profile: DshRemoteProfile?) {
        if (closed) return
        cancelSelection()
        profile?.keyId?.let { unsavedKeys.remove(it) }
        clearUnsavedKeys()
        hostValue = profile?.host.orEmpty()
        userValue = profile?.username.orEmpty()
        sshPortValue = profile?.sshPort?.toString() ?: DEFAULT_SSH_PORT.toString()
        dshPortValue = profile?.remoteDshPort?.toString() ?: DEFAULT_DSH_PORT.toString()
        keyId = profile?.keyId.orEmpty()
        fingerprint = profile?.hostFingerprint.orEmpty()
        passphraseValue = ""
        error = ""
        keyLabel = if (keyId.isEmpty()) NO_KEY_LABEL else KEY_IMPORTED_LABEL
        edited()
    }

    /** True when enough is filled in to try a connection without asking the user. */
    val isConfigured: Boolean
        get() = host.isNotBlank() && user.isNotBlank() && keyId.isNotBlank()

    /** Returns the profile, or sets [error] and returns null when a field is invalid. */
    fun validate(): DshRemoteProfile? {
        if (closed) return null
        val port = sshPort.toIntOrNull()
        val remotePort = dshPort.toIntOrNull()
        error = when {
            host.isBlank() -> "请输入 SSH 主机地址"
            user.isBlank() -> "请输入 SSH 用户名"
            port == null || port !in 1..65535 -> "SSH 端口无效"
            remotePort == null || remotePort !in 1..65535 -> "远程 DSH 端口无效"
            keyId.isBlank() -> "请先导入 SSH 私钥"
            else -> { error = ""; return toProfile() }
        }
        return null
    }

    /** Current fields as a profile; unparsable ports fall back to the defaults. */
    fun toProfile(hostFingerprint: String = fingerprint): DshRemoteProfile = DshRemoteProfile(
        host = host.trim(),
        sshPort = sshPort.toIntOrNull() ?: DEFAULT_SSH_PORT,
        username = user.trim(),
        remoteDshPort = dshPort.toIntOrNull() ?: DEFAULT_DSH_PORT,
        keyId = keyId,
        hostFingerprint = hostFingerprint,
    )
    fun toSshConfig(profile: DshRemoteProfile = toProfile()): DshSshConfig = DshSshConfig(
        host = profile.host,
        port = profile.sshPort,
        username = profile.username,
        remoteDshPort = profile.remoteDshPort,
        keyId = profile.keyId,
        hostFingerprint = profile.hostFingerprint,
        keyPassphrase = passphrase,
    )

    /** Let the user pick a private key file and import it into the native key store. */
    fun pickKey() {
        if (closed || busy) return
        val request = Selection()
        selection = request
        edited()
        busy = true
        error = ""
        try {
            pickFile { uri -> post {
                if (!accepts(request) || request.importing || request.completed) return@post
                if (uri.isEmpty()) { finish(request); return@post }
                request.importing = true
                try {
                    importFile(uri) { imported ->
                        // Pager-scoped scheduling stops on destroy. Reclaim invalid results before scheduling.
                        if (request.delivered && imported == request.deliveredKey) return@importFile
                        if (!accepts(request) || request.completed || request.delivered) discardImport(request, imported)
                        else {
                            request.delivered = true
                            request.deliveredKey = imported
                            if (imported.isNotEmpty() && imported != keyId) unsavedKeys.add(imported)
                            post { applyImport(request, imported) }
                        }
                    }
                } catch (_: Exception) { applyImport(request, "") }
            } }
        } catch (_: Exception) { post { applyImport(request, "") } }
    }

    private fun accepts(request: Selection) = !closed && selection === request

    private fun applyImport(request: Selection, imported: String) {
        if (!accepts(request) || request.completed) {
            discardImport(request, imported)
            return
        }
        finish(request)
        if (imported.isEmpty()) { error = "无法导入 SSH 私钥"; return }
        clearUnsavedKeys(except = imported)
        request.acceptedKey = imported
        unsavedKeys.add(imported)
        keyId = imported
        keyLabel = KEY_IMPORTED_LABEL
        error = ""
        edited()
    }

    private fun finish(request: Selection) { request.completed = true; busy = false }

    private fun discardImport(request: Selection, imported: String) {
        if (imported.isNotEmpty() && imported != request.acceptedKey && imported != keyId && request.discarded.add(imported)) {
            runCatching { deleteKey(imported) }
        }
    }

    fun cancelSelection() {
        selection = null
        busy = false
    }

    /** Persisted profiles and the next page take ownership of this key. */
    fun markKeySaved(id: String = keyId) { unsavedKeys.remove(id) }

    private fun clearUnsavedKeys(except: String = "") {
        for (id in unsavedKeys.toList()) {
            if (id == except) continue
            runCatching { deleteKey(id) }.onSuccess { unsavedKeys.remove(id) }
        }
    }

    fun dispose() {
        if (closed) return
        closed = true
        cancelSelection()
        clearUnsavedKeys()
        passphraseValue = ""
        onEdit = {}
        revision++
    }

    companion object {
        const val DEFAULT_SSH_PORT = 22
        const val DEFAULT_DSH_PORT = 3080
        const val NO_KEY_LABEL = "未导入 SSH 私钥"
        const val KEY_IMPORTED_LABEL = "已导入 SSH 私钥"
    }
}
