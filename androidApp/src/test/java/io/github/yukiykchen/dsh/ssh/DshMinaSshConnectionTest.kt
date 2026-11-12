package io.github.yukiykchen.dsh.ssh

import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

class DshMinaSshConnectionTest {
    @Test fun closeCancelsAuthenticationWaitingForTheRemoteServer() {
        withServer { server, identity ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { _, _, _ ->
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                true
            }
            val executor = Executors.newSingleThreadExecutor()
            val connection = DshMinaSshConnection(config(server)) { true }
            try {
                connection.connect()
                val authenticating = executor.submit(java.util.concurrent.Callable {
                    runCatching { connection.authenticate(privateBytes(identity), "") }
                })
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                connection.close()
                assertTrue(authenticating.get(10, TimeUnit.SECONDS).isFailure)
                assertFalse(connection.isOpen())
            } finally {
                release.countDown()
                connection.close()
                executor.shutdownNow()
            }
        }
    }

    @Test fun realSshAuthenticationAndLoopbackForwardingPreserveBinaryTraffic() {
        withServer { server, identity ->
            ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { echo ->
                val executor = Executors.newSingleThreadExecutor()
                val size = 128 * 1024
                val echoing = executor.submit {
                    echo.accept().use { peer ->
                        val input = peer.getInputStream()
                        val output = peer.getOutputStream()
                        val buffer = ByteArray(4096)
                        var remaining = size
                        while (remaining > 0) {
                            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                            check(count > 0)
                            output.write(buffer, 0, count)
                            output.flush()
                            remaining -= count
                        }
                    }
                }
                val connection = DshMinaSshConnection(config(server)) { it == KeyUtils.getFingerPrint(server.keyPairProvider.loadKeys(null).first().public) }
                try {
                    connection.connect()
                    val bytes = privateBytes(identity)
                    connection.authenticate(bytes, "")
                    assertTrue(bytes.all { it == 0.toByte() })
                    val port = connection.forward(echo.localPort)
                    assertTrue(connection.isOpen())
                    Socket("127.0.0.1", port).use { socket ->
                        socket.soTimeout = 10_000
                        val payload = ByteArray(size) { (it % 251).toByte() }
                        socket.getOutputStream().write(payload)
                        val received = ByteArray(size)
                        java.io.DataInputStream(socket.getInputStream()).readFully(received)
                        assertArrayEquals(payload, received)
                    }
                    echoing.get(10, TimeUnit.SECONDS)
                } finally {
                    connection.close()
                    executor.shutdownNow()
                }
                assertFalse(connection.isOpen())
            }
        }
    }

    @Test fun rejectedHostFingerprintCannotAuthenticateOrCreateATunnel() {
        withServer { server, identity ->
            var offered = ""
            val connection = DshMinaSshConnection(config(server)) { offered = it; false }
            try {
                connection.connect()
                assertThrows(DshSshFailure::class.java) { connection.authenticate(privateBytes(identity), "") }
                assertTrue(offered.startsWith("SHA256:"))
            } finally { connection.close() }
            assertFalse(connection.isOpen())
        }
    }

    @Test fun authenticationFailureIsPermanentAndCloseIsIdempotent() {
        withServer { server, _ ->
            val connection = DshMinaSshConnection(config(server)) { true }
            val wrong = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            try {
                connection.connect()
                val failure = assertThrows(DshSshFailure::class.java) { connection.authenticate(privateBytes(wrong), "") }
                assertFalse(failure.retryable)
            } finally { connection.close(); connection.close() }
            assertFalse(connection.isOpen())
        }
    }

    private fun withServer(block: (SshServer, java.security.KeyPair) -> Unit) {
        val identity = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val host = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val server = SshServer.setUpDefaultServer().apply {
            this.host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(host)
            publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { user, key, _ -> user == "user" && KeyUtils.compareKeys(identity.public, key) }
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        }
        server.start()
        try { block(server, identity) } finally { server.stop(true) }
    }

    private fun config(server: SshServer) = DshSshConfig("127.0.0.1", server.port, "user", 3080, "test-key")
    private fun privateBytes(pair: java.security.KeyPair) = ByteArrayOutputStream().also {
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, "test", null, it)
    }.toByteArray()
}
