package io.github.yukiykchen.dsh.ssh

internal fun interface DshSshCancellation { fun cancel() }

internal interface DshSshConnection {
    fun connect()
    fun authenticate(keyBytes: ByteArray, passphrase: String)
    fun forward(remotePort: Int): Int
    fun isOpen(): Boolean
    /** Abort I/O without waiting for the worker that may be calling the fingerprint verifier. */
    fun close()
}

internal class DshSshFailure(message: String, val retryable: Boolean, cause: Throwable? = null) : Exception(message, cause)

/** Only the current attempt may publish state; resources and key copies never belong to another attempt. */
internal class DshSshRuntime(
    private val factory: (DshSshConfig, (String) -> Boolean) -> DshSshConnection,
    private val work: (() -> Unit) -> DshSshCancellation,
    private val schedule: (Long, () -> Unit) -> DshSshCancellation,
    private val log: (String) -> Unit = {},
) {
    private class Attempt(val generation: Long, val key: ByteArray, var passphrase: String) {
        var connection: DshSshConnection? = null
        var worker: DshSshCancellation? = null
        var monitor: DshSshCancellation? = null
        var deadline: DshSshCancellation? = null
        var offeredFingerprint = ""
    }

    private val lock = Any()
    private val listeners = mutableListOf<(SshState) -> Unit>()
    private var generation = 0L
    private var enabled = false
    private var config: DshSshConfig? = null
    private var key: ByteArray? = null
    private var passphrase = ""
    private var trustedFingerprint = ""
    private var pendingFingerprint = ""
    private var active: Attempt? = null
    private var retry: DshSshCancellation? = null
    private var retryIndex = 0
    private var retryableFailure = false
    @Volatile private var state = SshState(SshPhase.IDLE)

    fun current() = state
    fun endpoint(): String? = state.takeIf { it.phase == SshPhase.READY && it.localPort > 0 }
        ?.let { "http://127.0.0.1:${it.localPort}" }

    fun addListener(replay: Boolean = true, listener: (SshState) -> Unit) = synchronized(lock) {
        listeners.add(listener)
        if (replay) runCatching { listener(state) }.onFailure { log("SSH listener failed") }
        Unit
    }
    fun removeListener(listener: (SshState) -> Unit) = synchronized(lock) { listeners.remove(listener); Unit }

    fun connect(next: DshSshConfig, bytes: ByteArray, secret: String = "") = synchronized(lock) {
        require(next.host.isNotBlank() && next.username.isNotBlank() && next.port in 1..65535 && next.remoteDshPort in 1..65535) {
            "Invalid SSH connection settings"
        }
        require(bytes.size in 1..64 * 1024) { "Invalid SSH private key size" }
        stopResources()
        enabled = true
        config = next
        key = bytes.copyOf()
        passphrase = secret
        trustedFingerprint = next.hostFingerprint
        pendingFingerprint = ""
        retryIndex = 0
        startAttempt()
    }

    fun disconnect() = synchronized(lock) {
        stopResources()
        config = null
        trustedFingerprint = ""
        pendingFingerprint = ""
        emit(SshState(SshPhase.STOPPED, "SSH 已断开", generation = ++generation))
    }

    fun acceptFingerprint(fingerprint: String) = synchronized(lock) {
        if (!enabled || state.phase != SshPhase.FINGERPRINT_REQUIRED || fingerprint.isEmpty() || fingerprint != pendingFingerprint) return@synchronized
        trustedFingerprint = fingerprint
        pendingFingerprint = ""
        retryIndex = 0
        startAttempt()
    }

    fun networkLost() = synchronized(lock) {
        if (!enabled || state.phase == SshPhase.FINGERPRINT_REQUIRED) return@synchronized
        active?.let { fail(it, DshSshFailure("网络已切换，正在重连", true)) }
    }

    fun networkAvailable() = synchronized(lock) {
        if (enabled && active == null && retryableFailure && state.phase in setOf(SshPhase.ERROR, SshPhase.RECONNECTING)) {
            startAttempt()
        }
    }

    private fun startAttempt() {
        retry?.cancel()
        retry = null
        releaseAttempt()
        val settings = config ?: return
        val material = key ?: return
        val attempt = Attempt(++generation, material.copyOf(), passphrase)
        active = attempt
        retryableFailure = false
        try {
            attempt.connection = factory(settings) { fingerprint -> synchronized(lock) {
                if (!accepts(attempt) || fingerprint.isEmpty()) return@synchronized false
                if (trustedFingerprint.isNotEmpty() && fingerprint == trustedFingerprint) return@synchronized true
                attempt.offeredFingerprint = fingerprint
                pendingFingerprint = fingerprint
                emit(SshState(SshPhase.FINGERPRINT_REQUIRED, fingerprint, generation = attempt.generation))
                false
            } }
            emit(SshState(SshPhase.CONNECTING, "正在连接 SSH", generation = attempt.generation))
            if (!accepts(attempt)) return
            val deadline = schedule(30_000) { synchronized(lock) {
                if (accepts(attempt) && state.phase != SshPhase.READY) fail(attempt, DshSshFailure("SSH 连接超时", true))
            } }
            if (accepts(attempt)) attempt.deadline = deadline else { deadline.cancel(); return }
            val worker = work { establish(attempt, settings.remoteDshPort) }
            if (accepts(attempt)) attempt.worker = worker else worker.cancel()
        } catch (error: Exception) { fail(attempt, error) }
    }

    private fun establish(attempt: Attempt, remotePort: Int) {
        val connection = attempt.connection ?: return
        try {
            if (!synchronized(lock) { accepts(attempt) }) return
            connection.connect()
            synchronized(lock) {
                if (!accepts(attempt)) return
                emit(SshState(SshPhase.AUTHENTICATING, "正在验证 SSH 身份", generation = attempt.generation))
                if (!accepts(attempt)) return
            }
            try { connection.authenticate(attempt.key, attempt.passphrase) }
            finally { attempt.key.fill(0); attempt.passphrase = "" }
            synchronized(lock) {
                if (!accepts(attempt)) return
                emit(SshState(SshPhase.FORWARDING, "正在建立 DSH 转发", generation = attempt.generation))
                if (!accepts(attempt)) return
            }
            val port = connection.forward(remotePort)
            synchronized(lock) {
                if (!accepts(attempt)) return
                require(port in 1..65535) { "Invalid SSH forwarding port" }
                attempt.deadline?.cancel()
                attempt.deadline = null
                retryIndex = 0
                emit(SshState(SshPhase.READY, "SSH 已连接", port, attempt.generation))
                if (accepts(attempt)) monitor(attempt)
            }
        } catch (error: Exception) { synchronized(lock) { fail(attempt, error) } }
        finally {
            attempt.key.fill(0)
            attempt.passphrase = ""
            if (!synchronized(lock) { accepts(attempt) }) connection.close()
        }
    }

    private fun monitor(attempt: Attempt) {
        val timer = schedule(15_000) {
            val open = runCatching { attempt.connection?.isOpen() == true }.getOrDefault(false)
            synchronized(lock) {
                if (!accepts(attempt)) return@synchronized
                if (open) monitor(attempt) else fail(attempt, DshSshFailure("SSH 连接已断开，正在重连", true))
            }
        }
        if (accepts(attempt)) attempt.monitor = timer else timer.cancel()
    }

    private fun fail(attempt: Attempt, error: Exception) {
        if (!accepts(attempt)) return
        if (attempt.offeredFingerprint.isNotEmpty()) {
            val offered = attempt.offeredFingerprint
            releaseAttempt()
            emit(SshState(SshPhase.FINGERPRINT_REQUIRED, offered, generation = attempt.generation))
            return
        }
        retryableFailure = (error as? DshSshFailure)?.retryable == true
        releaseAttempt()
        log("SSH attempt ${attempt.generation} failed (${error.javaClass.simpleName})")
        emit(SshState(if (retryableFailure) SshPhase.RECONNECTING else SshPhase.ERROR,
            (error as? DshSshFailure)?.message ?: "SSH 连接失败", generation = attempt.generation))
        if (!enabled || generation != attempt.generation || !retryableFailure || retryIndex >= RETRY_DELAYS.size) return
        val retryGeneration = generation
        val timer = schedule(RETRY_DELAYS[retryIndex++]) { synchronized(lock) {
            if (enabled && active == null && generation == retryGeneration && retryableFailure) startAttempt()
        } }
        if (enabled && generation == retryGeneration && active == null) retry = timer else timer.cancel()
    }

    private fun accepts(attempt: Attempt) = enabled && active === attempt && generation == attempt.generation

    private fun releaseAttempt() {
        val attempt = active ?: return
        active = null
        attempt.deadline?.cancel()
        attempt.monitor?.cancel()
        attempt.worker?.cancel()
        attempt.key.fill(0)
        attempt.passphrase = ""
        runCatching { attempt.connection?.close() }.onFailure { log("SSH resource cleanup failed") }
    }

    private fun stopResources() {
        enabled = false
        retry?.cancel()
        retry = null
        releaseAttempt()
        key?.fill(0)
        key = null
        passphrase = ""
        retryableFailure = false
    }

    private fun emit(next: SshState) {
        state = next
        for (listener in listeners.toList()) {
            if (state !== next) break
            runCatching { listener(next) }.onFailure { log("SSH listener failed") }
        }
    }

    companion object { private val RETRY_DELAYS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000) }
}
