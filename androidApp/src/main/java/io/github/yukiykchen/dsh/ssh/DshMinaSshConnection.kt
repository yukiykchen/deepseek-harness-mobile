package io.github.yukiykchen.dsh.ssh

import org.apache.sshd.client.SshClient
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory
import org.apache.sshd.client.future.AuthFuture
import org.apache.sshd.client.future.ConnectFuture
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.core.CoreModuleProperties
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One MINA client per attempt: closing it cannot stop another attempt or change its identities. */
internal class DshMinaSshConnection(
    private val config: DshSshConfig,
    private val verify: (String) -> Boolean,
) : DshSshConnection {
    private val lock = Any()
    private var closed = false
    private var client: SshClient? = null
    private var connecting: ConnectFuture? = null
    private var authenticating: AuthFuture? = null
    private var session: ClientSession? = null

    override fun connect() {
        try {
            val future = synchronized(lock) {
                check(!closed) { "SSH connection cancelled" }
                val ssh = SshClient.setUpDefaultClient()
                client = ssh
                ssh.serverKeyVerifier = org.apache.sshd.client.keyverifier.ServerKeyVerifier { _, _, serverKey ->
                    verify(KeyUtils.getFingerPrint(serverKey))
                }
                // Do not search app files for default identities or try interactive/password authentication.
                ssh.keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER
                ssh.userAuthFactories = listOf(UserAuthPublicKeyFactory.INSTANCE)
                CoreModuleProperties.HEARTBEAT_INTERVAL.set(ssh, Duration.ofSeconds(15))
                CoreModuleProperties.HEARTBEAT_REQUEST.set(ssh, "keepalive@openssh.com")
                CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(ssh, 3)
                ssh.start()
                ssh.connect(config.username, config.host, config.port).also { connecting = it }
            }
            val connected = future.verify(10_000, TimeUnit.MILLISECONDS).session
            synchronized(lock) {
                if (closed) { connected.close(true); throw IllegalStateException("SSH connection cancelled") }
                session = connected
                connecting = null
            }
        } catch (error: Exception) { throw DshSshFailure("无法连接 SSH 主机", true, error) }
    }

    override fun authenticate(keyBytes: ByteArray, passphrase: String) {
        try {
            val keys = keyBytes.inputStream().use {
                SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName("imported-ssh-key"), it,
                    if (passphrase.isEmpty()) FilePasswordProvider.EMPTY else FilePasswordProvider.of(passphrase))?.toList()
            }
            require(!keys.isNullOrEmpty()) { "SSH key contains no identities" }
            val future = synchronized(lock) {
                check(!closed) { "SSH connection cancelled" }
                val active = requireNotNull(session)
                keys.forEach(active::addPublicKeyIdentity)
                active.auth().also { authenticating = it }
            }
            future.verify(10_000, TimeUnit.MILLISECONDS)
            synchronized(lock) { check(!closed); authenticating = null }
        } catch (error: Exception) {
            throw DshSshFailure("SSH 身份验证失败，请检查用户名、私钥和口令", false, error)
        } finally { keyBytes.fill(0) }
    }

    override fun forward(remotePort: Int): Int {
        try {
            val active = synchronized(lock) { check(!closed); requireNotNull(session) }
            val address = active.startLocalPortForwarding(SshdSocketAddress("127.0.0.1", 0), SshdSocketAddress("127.0.0.1", remotePort))
            synchronized(lock) { check(!closed) }
            return address.port
        } catch (error: Exception) { throw DshSshFailure("本地 SSH 转发创建失败", false, error) }
    }

    override fun isOpen(): Boolean = synchronized(lock) { !closed && session?.isOpen == true }

    override fun close() {
        val ssh = synchronized(lock) {
            if (closed) return
            closed = true
            connecting?.cancel()
            connecting = null
            authenticating?.cancel()
            authenticating = null
            session?.close(true)
            session = null
            client.also { client = null }
        }
        // stop() waits for MINA service threads; never wait while the runtime owns its state lock.
        if (ssh != null) DshSshWorkers.cleanup { runCatching { ssh.stop() } }
    }
}

internal object DshSshWorkers {
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "dsh-ssh-io").apply { isDaemon = true } }
    private val timers = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "dsh-ssh-timer").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    fun work(action: () -> Unit): DshSshCancellation {
        val future = workers.submit(action)
        return DshSshCancellation { future.cancel(true) }
    }
    fun cleanup(action: () -> Unit) { workers.execute(action) }
    fun schedule(delay: Long, action: () -> Unit): DshSshCancellation {
        val future = timers.schedule(action, delay, TimeUnit.MILLISECONDS)
        return DshSshCancellation { future.cancel(false) }
    }
}
