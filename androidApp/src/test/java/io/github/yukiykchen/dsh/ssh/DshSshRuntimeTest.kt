package io.github.yukiykchen.dsh.ssh

import org.junit.Assert.*
import org.junit.Test

class DshSshRuntimeTest {
    @Test fun replacementSubscriptionDoesNotReplayThePreviousProfilesReadyEndpoint() {
        val f = Fixture()
        f.connect()
        f.workers.first().runEvenIfCancelled()
        val seen = mutableListOf<SshPhase>()
        f.runtime.addListener(replay = false) { seen.add(it.phase) }
        assertTrue(seen.isEmpty())
        f.connect("another-host")
        assertEquals(listOf(SshPhase.CONNECTING), seen)
    }

    @Test fun stopBeforeQueuedWorkerCannotStartNativeIo() {
        val f = Fixture()
        f.connect()
        f.runtime.disconnect()
        f.workers.first().runEvenIfCancelled()
        assertEquals(0, f.connections.first().connectCount)
        assertTrue(f.connections.first().closed)
        assertEquals(SshPhase.STOPPED, f.runtime.current().phase)
    }

    @Test fun cancelledAuthenticationCannotReplaceANewerConnection() {
        val f = Fixture()
        f.connect()
        val old = f.connections.first()
        old.onAuth = { f.connect("second"); f.workers.last().runEvenIfCancelled() }
        f.workers.first().runEvenIfCancelled()
        assertEquals(0, old.forwardCount)
        assertTrue(old.closed)
        assertFalse(f.connections.last().closed)
        assertEquals(f.connections.last().port, f.runtime.current().localPort)
    }

    @Test fun forwardingReturningAfterDisconnectCannotPublishReady() {
        val f = Fixture()
        f.connect()
        f.connections.first().onForward = { f.runtime.disconnect() }
        f.workers.first().runEvenIfCancelled()
        assertEquals(SshPhase.STOPPED, f.runtime.current().phase)
        assertNull(f.runtime.endpoint())
    }

    @Test fun authenticationFailureClosesTheUnpublishedSessionAndDoesNotRetry() {
        val f = Fixture()
        f.connect()
        f.connections.first().onAuth = { throw DshSshFailure("invalid key", false) }
        f.workers.first().runEvenIfCancelled()
        assertTrue(f.connections.first().closed)
        assertEquals(SshPhase.ERROR, f.runtime.current().phase)
        assertTrue(f.timers.all { it.cancelled })
    }

    @Test fun staleReconnectTimerCannotRestartAfterExplicitStopOrNewConnect() {
        val f = Fixture()
        f.connect()
        f.connections.first().onConnect = { throw DshSshFailure("unavailable", true) }
        f.workers.first().runEvenIfCancelled()
        val retry = f.timers.last()
        assertEquals(1000L, retry.delay)
        f.runtime.disconnect()
        retry.runEvenIfCancelled()
        assertEquals(1, f.connections.size)
        f.connect()
        retry.runEvenIfCancelled()
        assertEquals(2, f.connections.size)
    }

    @Test fun hostKeyMismatchRequiresTheExactOfferedFingerprintAndNeverAutoRetries() {
        val f = Fixture()
        f.connect()
        f.connections.first().fingerprint = "SHA256:new"
        f.workers.first().runEvenIfCancelled()
        assertEquals(SshPhase.FINGERPRINT_REQUIRED, f.runtime.current().phase)
        assertTrue(f.connections.first().closed)
        f.runtime.networkAvailable()
        f.runtime.networkLost()
        f.runtime.acceptFingerprint("SHA256:other")
        assertEquals(1, f.connections.size)
        f.runtime.acceptFingerprint("SHA256:new")
        f.connections.last().fingerprint = "SHA256:new"
        f.workers.last().runEvenIfCancelled()
        assertEquals(SshPhase.READY, f.runtime.current().phase)
    }

    @Test fun fingerprintCanBeAcceptedReentrantlyBeforeTheOldWorkerFinishes() {
        val f = Fixture()
        f.runtime.addListener { if (it.phase == SshPhase.FINGERPRINT_REQUIRED) f.runtime.acceptFingerprint(it.message) }
        f.connect()
        f.connections.first().fingerprint = "SHA256:new"
        f.workers.first().runEvenIfCancelled()
        assertEquals(2, f.connections.size)
        f.connections.last().fingerprint = "SHA256:new"
        f.workers.last().runEvenIfCancelled()
        assertEquals(SshPhase.READY, f.runtime.current().phase)
        assertFalse(f.connections.last().closed)
    }

    @Test fun listenerStoppingAtReadyCannotDeliverStaleReadyToTheNextListener() {
        val f = Fixture()
        val seen = mutableListOf<SshPhase>()
        f.runtime.addListener { if (it.phase == SshPhase.READY) f.runtime.disconnect() }
        f.runtime.addListener { seen.add(it.phase) }
        f.connect()
        f.workers.first().runEvenIfCancelled()
        assertFalse(seen.contains(SshPhase.READY))
        assertEquals(SshPhase.STOPPED, seen.last())
    }

    @Test fun networkLossClearsTheEndpointAndReleasesOnlyTheCurrentAttempt() {
        val f = Fixture()
        f.connect()
        f.workers.first().runEvenIfCancelled()
        val generation = f.runtime.current().generation
        assertNotNull(f.runtime.endpoint())
        f.runtime.networkLost()
        assertNull(f.runtime.endpoint())
        assertTrue(f.connections.first().closed)
        f.runtime.networkAvailable()
        f.runtime.networkAvailable()
        assertEquals(2, f.connections.size)
        f.workers.last().runEvenIfCancelled()
        assertTrue(f.runtime.current().generation > generation)
    }

    @Test fun deadlineCancelsAPendingAttemptAndIsRemovedAfterReady() {
        val f = Fixture()
        f.connect()
        f.timers.first().runEvenIfCancelled()
        f.workers.first().runEvenIfCancelled()
        assertEquals(SshPhase.RECONNECTING, f.runtime.current().phase)
        assertEquals(0, f.connections.first().connectCount)
        f.runtime.networkAvailable()
        val deadline = f.timers.last()
        f.workers.last().runEvenIfCancelled()
        assertTrue(deadline.cancelled)
        deadline.runEvenIfCancelled()
        assertEquals(SshPhase.READY, f.runtime.current().phase)
    }

    @Test fun monitorDetectsClosureAndCannotAffectAReplacement() {
        val f = Fixture()
        f.connect()
        f.workers.first().runEvenIfCancelled()
        val monitor = f.timers.last()
        f.connections.first().open = false
        monitor.runEvenIfCancelled()
        assertEquals(SshPhase.RECONNECTING, f.runtime.current().phase)
        f.connect()
        f.workers.last().runEvenIfCancelled()
        monitor.runEvenIfCancelled()
        assertEquals(SshPhase.READY, f.runtime.current().phase)
    }

    @Test fun nativeExceptionsCannotExposePassphrasesThroughStateOrLogs() {
        val f = Fixture()
        f.connect()
        f.connections.first().onAuth = { throw IllegalStateException("secret-password private-key") }
        f.workers.first().runEvenIfCancelled()
        assertFalse(f.runtime.current().message.contains("secret-password"))
        assertTrue(f.logs.none { it.contains("secret-password") })
    }

    @Test fun workerCopiesAreClearedAndCallerOwnedKeyIsNotMutated() {
        val f = Fixture()
        val bytes = byteArrayOf(1, 2, 3)
        f.runtime.connect(f.config(), bytes)
        f.workers.first().runEvenIfCancelled()
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
        assertTrue(f.connections.first().authBytes!!.all { it == 0.toByte() })
        f.runtime.disconnect()
        assertTrue(f.timers.all { it.cancelled })
    }

    @Test fun invalidSettingsFailBeforeReplacingTheReadyConnection() {
        val f = Fixture()
        f.connect()
        f.workers.first().runEvenIfCancelled()
        assertThrows(IllegalArgumentException::class.java) { f.runtime.connect(f.config().copy(port = 0), byteArrayOf(1)) }
        assertEquals(SshPhase.READY, f.runtime.current().phase)
        assertFalse(f.connections.first().closed)
    }

    private class Task(val delay: Long, val action: () -> Unit) : DshSshCancellation {
        var cancelled = false
        override fun cancel() { cancelled = true }
        fun runEvenIfCancelled() = action()
    }

    private class Connection(val verify: (String) -> Boolean, val port: Int) : DshSshConnection {
        var closed = false
        var open = true
        var connectCount = 0
        var forwardCount = 0
        var fingerprint = "SHA256:trusted"
        var onConnect: () -> Unit = {}
        var onAuth: () -> Unit = {}
        var onForward: () -> Unit = {}
        var authBytes: ByteArray? = null
        override fun connect() {
            connectCount++
            onConnect()
            if (!verify(fingerprint)) throw DshSshFailure("untrusted key", true)
        }
        override fun authenticate(keyBytes: ByteArray, passphrase: String) { authBytes = keyBytes; onAuth() }
        override fun forward(remotePort: Int): Int { forwardCount++; onForward(); return port }
        override fun isOpen() = open && !closed
        override fun close() { closed = true }
    }

    private class Fixture {
        val workers = mutableListOf<Task>()
        val timers = mutableListOf<Task>()
        val connections = mutableListOf<Connection>()
        val logs = mutableListOf<String>()
        val runtime = DshSshRuntime(
            factory = { _, verify -> Connection(verify, 18000 + connections.size).also { connections.add(it) } },
            work = { Task(0, it).also { workers.add(it) } },
            schedule = { delay, action -> Task(delay, action).also { timers.add(it) } },
            log = logs::add,
        )
        fun config(host: String = "host") = DshSshConfig(host, 22, "user", 3080, "key", "SHA256:trusted")
        fun connect(host: String = "host") = runtime.connect(config(host), byteArrayOf(1, 2, 3), "secret-password")
    }
}
