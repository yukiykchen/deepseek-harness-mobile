package io.github.yukiykchen.dsh.feature.connection

import com.tencent.kuikly.core.base.PagerScope
import io.github.yukiykchen.dsh.data.DshHostClient
import io.github.yukiykchen.dsh.data.DshLocalStore
import io.github.yukiykchen.dsh.data.DshStoreCommand
import io.github.yukiykchen.dsh.data.DshStoreCommandData
import io.github.yukiykchen.dsh.data.FakeHostTransport
import io.github.yukiykchen.dsh.data.createTestHostClient
import io.github.yukiykchen.dsh.model.DshConnectionMode
import io.github.yukiykchen.dsh.model.DshLegacyRemoteProfile
import io.github.yukiykchen.dsh.model.DshMessage
import io.github.yukiykchen.dsh.model.DshRelayProfile
import io.github.yukiykchen.dsh.model.DshRemoteProfile
import io.github.yukiykchen.dsh.model.DshSession
import io.github.yukiykchen.dsh.platform.DshRelayPairResult
import io.github.yukiykchen.dsh.platform.DshSshConfig
import io.github.yukiykchen.dsh.platform.DshSshPhase
import io.github.yukiykchen.dsh.platform.DshSshState

internal class SetupTestFixture {
    data class Validation(val id: String, val passphrase: String, val callback: (Boolean) -> Unit)
    data class Start(val config: DshSshConfig, val callback: (DshSshState) -> Unit)
    data class Probe(val client: DshHostClient, val transport: FakeHostTransport) {
        fun success() = transport.requests.last { it.method == "session.list" }.reply("""{"items":[]}""")
        fun failure() = transport.requests.last { it.method == "session.list" }.fail("Host unreachable")
    }
    data class Navigation(val mode: DshConnectionMode, val profile: String, val passphrase: String)

    val posts = mutableListOf<() -> Unit>()
    val scans = mutableListOf<(DshRelayPairResult) -> Unit>()
    val forgets = mutableListOf<(Boolean) -> Unit>()
    val validations = mutableListOf<Validation>()
    val starts = mutableListOf<Start>()
    val trusts = mutableListOf<String>()
    val probes = mutableListOf<Probe>()
    val navigations = mutableListOf<Navigation>()
    val store = SetupTestStore()
    var storeAvailable = true
    var stops = 0
    var cancelledPairings = 0
    var onCreateProbe: () -> Unit = {}
    val scope = object : PagerScope { override val pagerId = "test-page" }
    val form = DshSshProfileForm(scope, pickFile = {}, post = { posts.add(it) })
    val controller = DshConnectionSetupController(scope, form, { store.takeIf { storeAvailable } }, object : DshConnectionSetupTransport {
        override fun scanAndPair(callback: (DshRelayPairResult) -> Unit) { scans.add(callback) }
        override fun cancelPairing() { cancelledPairings++ }
        override fun forget(callback: (Boolean) -> Unit) { forgets.add(callback) }
        override fun validateKey(id: String, passphrase: String, callback: (Boolean) -> Unit) { validations.add(Validation(id, passphrase, callback)) }
        override fun startSsh(config: DshSshConfig, callback: (DshSshState) -> Unit) { starts.add(Start(config, callback)) }
        override fun trustFingerprint(fingerprint: String) { trusts.add(fingerprint) }
        override fun stopSsh() { stops++ }
        override fun createProbe(port: Int): DshHostClient {
            val transport = FakeHostTransport()
            val client = createTestHostClient(transport)
            transport.completeHandshake()
            probes.add(Probe(client, transport))
            onCreateProbe()
            return client
        }
    }, { true }, { true }, { mode, id, secret -> navigations.add(Navigation(mode, id, secret)) }, { posts.add(it) })

    fun flush() { while (posts.isNotEmpty()) posts.removeAt(0)() }
    fun configureSsh() {
        controller.connectionMode = DshConnectionMode.SSH
        form.load(DshRemoteProfile(host = "host", sshPort = 22, username = "user", remoteDshPort = 3080, keyId = "key-1"))
        form.passphrase = "test-passphrase"
    }
    fun startSsh() {
        configureSsh()
        controller.continueToHost()
        validations.last().callback(true)
        flush()
    }
    fun state(phase: DshSshPhase, port: Int = 8811, generation: Long = 1, message: String = "") {
        starts.last().callback(DshSshState(phase, message, port, generation))
        flush()
    }
    fun ready() { startSsh(); state(DshSshPhase.READY) }
    fun pairing() = DshRelayPairResult(true, hostId = "host", hostName = "Host", relayOrigin = "https://relay.example", pairedAt = 100)
}

internal class SetupTestStore : DshLocalStore {
    var remote: DshRemoteProfile? = null
    var relay: DshRelayProfile? = null
    var mode: DshConnectionMode? = null
    var failWrites = false
    var remoteWrites = 0
    var closed = false
    val writes = mutableListOf<Pair<DshStoreCommand, () -> Unit>>()
    var deferWrites = false
    private fun writable() { check(!closed && !failWrites) { "store unavailable" } }
    override fun submitAsync(
        command: DshStoreCommand,
        data: DshStoreCommandData,
        post: (() -> Unit) -> Unit,
        onResult: (Throwable?) -> Unit,
    ) {
        if (closed || failWrites) {
            post { onResult(IllegalStateException("store unavailable")) }
            return
        }
        val result = {
            try {
                applyCommand(command, data)
                onResult(null)
            } catch (error: Throwable) {
                onResult(error)
            }
        }
        if (deferWrites) {
            writes.add(command to result)
        } else {
            post(result)
        }
    }
    fun completeWrites(post: (() -> Unit) -> Unit = { it() }) {
        val pending = writes.toList()
        writes.clear()
        pending.forEach { (_, work) -> post(work) }
    }
    private fun applyCommand(command: DshStoreCommand, data: DshStoreCommandData) = when (command) {
        DshStoreCommand.SAVE_RELAY_MODE -> saveLastConnectionMode(DshConnectionMode.RELAY)
        DshStoreCommand.SAVE_SSH_MODE -> saveLastConnectionMode(DshConnectionMode.SSH)
        DshStoreCommand.SAVE_RELAY_PROFILE -> saveRelayProfile(data.relayProfile!!)
        DshStoreCommand.CLEAR_RELAY_PROFILE -> clearRelayProfile()
        DshStoreCommand.SAVE_SSH_PROFILE -> {
            saveRemoteProfile(data.remoteProfile!!)
            saveLastConnectionMode(DshConnectionMode.SSH)
        }
    }
    override fun loadLastConnectionMode() = mode ?: DshConnectionMode.RELAY
    override fun saveLastConnectionMode(mode: DshConnectionMode) { writable(); this.mode = mode }
    override fun loadRemoteProfile() = remote
    override fun saveRemoteProfile(profile: DshRemoteProfile) { writable(); remote = profile; remoteWrites++ }
    override fun loadRelayProfile() = relay
    override fun saveRelayProfile(profile: DshRelayProfile) { writable(); relay = profile }
    override fun clearRelayProfile() { writable(); relay = null }
    override fun migrateLegacyRemoteProfile(profile: DshLegacyRemoteProfile) = false
    override fun loadSessions(scopeId: String) = emptyList<DshSession>()
    override fun replaceSessions(scopeId: String, sessions: List<DshSession>) = Unit
    override fun replaceSessionsAsync(scopeId: String, sessions: List<DshSession>) = Unit
    override fun loadMessages(scopeId: String, sessionId: String) = emptyList<DshMessage>()
    override fun replaceMessages(scopeId: String, sessionId: String, messages: List<DshMessage>) = Unit
    override fun clearScope(scopeId: String) = Unit
    override fun close() { closed = true }
}
