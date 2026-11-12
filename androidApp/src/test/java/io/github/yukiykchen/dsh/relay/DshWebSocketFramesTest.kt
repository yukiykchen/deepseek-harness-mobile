package io.github.yukiykchen.dsh.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull

class DshWebSocketFramesTest {
    @Test
    fun pingIsAnsweredLocallyAndNotForwardedAsText() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val mask = byteArrayOf(9, 8, 7, 6)
        val ping = DshWebSocketFrames.encodeMasked(DshWebSocketFrames.OPCODE_PING, payload, mask)
        val frame = DshWebSocketFrames.readFrame(ByteArrayInputStream(ping))
        requireNotNull(frame)
        assertTrue(DshWebSocketFrames.isPing(frame.opcode))
        assertArrayEquals(payload, frame.payload)
        assertTrue(!DshWebSocketFrames.isData(frame.opcode))

        val pong = DshWebSocketFrames.encodeUnmasked(DshWebSocketFrames.OPCODE_PONG, frame.payload)
        assertEquals(0x8a.toByte(), pong[0])
        assertEquals(4.toByte(), pong[1])
        assertArrayEquals(payload, pong.copyOfRange(2, pong.size))
    }

    @Test
    fun emptyPingEncodesEmptyPong() {
        val ping = DshWebSocketFrames.encodeMasked(
            DshWebSocketFrames.OPCODE_PING,
            ByteArray(0),
            byteArrayOf(1, 2, 3, 4),
        )
        val frame = DshWebSocketFrames.readFrame(ByteArrayInputStream(ping))
        requireNotNull(frame)
        val pong = DshWebSocketFrames.encodeUnmasked(DshWebSocketFrames.OPCODE_PONG, frame.payload)
        assertArrayEquals(byteArrayOf(0x8a.toByte(), 0), pong)
    }

    @Test
    fun closeAndPongAreControlFrames() {
        assertTrue(DshWebSocketFrames.isClose(DshWebSocketFrames.OPCODE_CLOSE))
        assertTrue(DshWebSocketFrames.isPong(DshWebSocketFrames.OPCODE_PONG))
        assertEquals(DshWebSocketFrames.OPCODE_TEXT, DshWebSocketFrames.forwardOpcode(1))
        assertEquals(DshWebSocketFrames.OPCODE_BINARY, DshWebSocketFrames.forwardOpcode(2))
        assertEquals(DshWebSocketFrames.OPCODE_TEXT, DshWebSocketFrames.forwardOpcode(9))
    }

    @Test
    fun textFrameRoundTrip() {
        val body = "{\"type\":\"session/event\"}".toByteArray()
        val masked = DshWebSocketFrames.encodeMasked(
            DshWebSocketFrames.OPCODE_TEXT,
            body,
            byteArrayOf(0x11, 0x22, 0x33, 0x44),
        )
        val frame = DshWebSocketFrames.readFrame(ByteArrayInputStream(masked))
        requireNotNull(frame)
        assertTrue(DshWebSocketFrames.isData(frame.opcode))
        assertEquals(DshWebSocketFrames.OPCODE_TEXT, frame.opcode)
        assertArrayEquals(body, frame.payload)
    }

    @Test
    fun largeAdvertisedLengthsAreRejectedBeforePayloadAllocationOrIntTruncation() {
        for (length in listOf(0x1_0000_0001L, Int.MAX_VALUE.toLong())) {
            val header = byteArrayOf(0x82.toByte(), 127) + ByteBuffer.allocate(8).putLong(length).array()
            assertThrows(IllegalArgumentException::class.java) { DshWebSocketFrames.readFrame(ByteArrayInputStream(header)) }
        }
    }

    @Test
    fun maskReservedBitsAndControlFrameRulesAreChecked() {
        assertThrows(IllegalArgumentException::class.java) {
            DshWebSocketFrames.readFrame(ByteArrayInputStream(byteArrayOf(0x81.toByte(), 0)), requireMasked = true)
        }
        for (bytes in listOf(byteArrayOf(0xc1.toByte(), 0), byteArrayOf(0x89.toByte(), 126, 0, 126), byteArrayOf(9, 0))) {
            assertThrows(IllegalArgumentException::class.java) { DshWebSocketFrames.readFrame(ByteArrayInputStream(bytes)) }
        }
    }

    @Test
    fun extendedLengthsRoundTripWithoutLosingHighBytes() {
        for (size in listOf(126, 65536)) {
            val data = ByteArray(size) { (it % 251).toByte() }
            val encoded = DshWebSocketFrames.encodeMasked(2, data, byteArrayOf(1, 2, 3, 4))
            assertArrayEquals(data, DshWebSocketFrames.readFrame(ByteArrayInputStream(encoded), true)!!.payload)
        }
    }

    @Test
    fun binaryFragmentsAreReassembledAroundInterleavedPingFrames() {
        val messages = DshWebSocketMessages()
        assertNull(messages.accept(DshWebSocketFrames.Frame(2, byteArrayOf(1, 2), false)))
        assertEquals(9, messages.accept(DshWebSocketFrames.Frame(9, ByteArray(0)))!!.opcode)
        assertNull(messages.accept(DshWebSocketFrames.Frame(0, byteArrayOf(3), false)))
        val message = messages.accept(DshWebSocketFrames.Frame(0, byteArrayOf(4)))!!
        assertEquals(2, message.opcode)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), message.payload)
    }

    @Test
    fun fragmentSequenceAndAggregateLengthAreValidated() {
        assertThrows(IllegalArgumentException::class.java) { DshWebSocketMessages().accept(DshWebSocketFrames.Frame(0, ByteArray(0))) }
        val messages = DshWebSocketMessages(8)
        messages.accept(DshWebSocketFrames.Frame(2, ByteArray(5), false))
        assertThrows(IllegalArgumentException::class.java) { messages.accept(DshWebSocketFrames.Frame(1, ByteArray(0))) }
        assertThrows(IllegalArgumentException::class.java) { messages.accept(DshWebSocketFrames.Frame(0, ByteArray(4))) }
        assertThrows(CharacterCodingException::class.java) { DshWebSocketMessages().accept(DshWebSocketFrames.Frame(1, byteArrayOf(0xff.toByte()))) }
    }
}
