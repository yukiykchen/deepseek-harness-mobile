package io.github.yukiykchen.dsh.ssh

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Paths
import java.security.KeyPairGenerator
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DshSshDeviceTest {
    @Test fun productionMinaClientAuthenticatesAndForwardsBinaryTrafficOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PathUtils.setUserHomeFolderResolver { Paths.get(context.filesDir.absolutePath) }
        val identity = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val host = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val server = SshServer.setUpDefaultServer().apply {
            this.host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(host)
            publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { user, key, _ ->
                user == "test-user" && KeyUtils.compareKeys(identity.public, key)
            }
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            addChannelListener(object : org.apache.sshd.common.channel.ChannelListener {
                override fun channelOpenFailure(channel: org.apache.sshd.common.channel.Channel, reason: Throwable) {
                    android.util.Log.e("DshSshDeviceTest", "Server channel open failed", reason)
                }
            })
        }
        val executor = Executors.newSingleThreadExecutor()
        val echo = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        var connection: DshMinaSshConnection? = null
        try {
            server.start()
            val echoing = executor.submit {
                echo.accept().use { peer ->
                    peer.soTimeout = 10_000
                    val bytes = ByteArray(64 * 1024)
                    DataInputStream(peer.getInputStream()).readFully(bytes)
                    peer.getOutputStream().write(bytes)
                }
            }
            val active = DshMinaSshConnection(DshSshConfig("127.0.0.1", server.port, "test-user", echo.localPort, "test-key")) {
                it == KeyUtils.getFingerPrint(host.public)
            }
            connection = active
            active.connect()
            val privateKey = ByteArrayOutputStream().also {
                OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(identity, "test", null, it)
            }.toByteArray()
            active.authenticate(privateKey, "")
            assertTrue(privateKey.all { it == 0.toByte() })
            Socket("127.0.0.1", active.forward(echo.localPort)).use { socket ->
                socket.soTimeout = 10_000
                val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
                socket.getOutputStream().write(payload)
                val received = ByteArray(payload.size)
                var offset = 0
                while (offset < received.size) {
                    val count = socket.getInputStream().read(received, offset, received.size - offset)
                    check(count > 0) { "SSH response truncated at $offset/${received.size}; echo completed=${echoing.isDone}" }
                    offset += count
                }
                assertArrayEquals(payload, received)
            }
            echoing.get(10, TimeUnit.SECONDS)
        } finally {
            connection?.close()
            echo.close()
            executor.shutdownNow()
            server.stop(true)
        }
        assertFalse(connection!!.isOpen())
    }
}
