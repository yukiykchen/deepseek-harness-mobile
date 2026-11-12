package io.github.yukiykchen.dsh.relay

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** RFC 6455 frames for the Relay loopback gateway. */
internal object DshWebSocketFrames {
    const val OPCODE_CONTINUATION = 0
    const val OPCODE_TEXT = 1
    const val OPCODE_BINARY = 2
    const val OPCODE_CLOSE = 8
    const val OPCODE_PING = 9
    const val OPCODE_PONG = 10
    const val MAX_MESSAGE_BYTES = 16 * 1024 * 1024

    data class Frame(val opcode: Int, val payload: ByteArray, val final: Boolean = true)

    fun isClose(opcode: Int): Boolean = opcode == OPCODE_CLOSE
    fun isPing(opcode: Int): Boolean = opcode == OPCODE_PING
    fun isPong(opcode: Int): Boolean = opcode == OPCODE_PONG
    fun isData(opcode: Int): Boolean =
        opcode == OPCODE_CONTINUATION || opcode == OPCODE_TEXT || opcode == OPCODE_BINARY

    fun forwardOpcode(opcode: Int): Int = if (opcode == OPCODE_BINARY) OPCODE_BINARY else OPCODE_TEXT

    fun encodeUnmasked(opcode: Int, payload: ByteArray): ByteArray {
        val headerSize = when {
            payload.size < 126 -> 2
            payload.size <= 0xffff -> 4
            else -> 10
        }
        val out = ByteArray(headerSize + payload.size)
        out[0] = (0x80 or (opcode and 0x0f)).toByte()
        writeLength(out, payload.size, masked = false)
        payload.copyInto(out, headerSize)
        return out
    }

    fun encodeMasked(opcode: Int, payload: ByteArray, mask: ByteArray): ByteArray {
        require(mask.size == 4)
        val headerSize = when {
            payload.size < 126 -> 6
            payload.size <= 0xffff -> 8
            else -> 14
        }
        val out = ByteArray(headerSize + payload.size)
        out[0] = (0x80 or (opcode and 0x0f)).toByte()
        writeLength(out, payload.size, masked = true)
        val maskAt = headerSize - 4
        mask.copyInto(out, maskAt)
        for (i in payload.indices) {
            out[headerSize + i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return out
    }

    fun readFrame(input: InputStream, requireMasked: Boolean = false): Frame? {
        val b1 = input.read()
        val b2 = input.read()
        if (b1 < 0 || b2 < 0) return null
        val opcode = b1 and 0x0f
        require(b1 and 0x70 == 0) { "unsupported WebSocket extension" }
        require(isData(opcode) || isClose(opcode) || isPing(opcode) || isPong(opcode)) { "invalid WebSocket opcode" }
        val final = b1 and 0x80 != 0
        val masked = b2 and 0x80 != 0
        require(!requireMasked || masked) { "client WebSocket frames must be masked" }
        val lengthTag = b2 and 0x7f
        var length = lengthTag.toLong()
        if (lengthTag == 126) {
            val extra = readExact(input, 2) ?: return null
            length = ((extra[0].toInt() and 0xff) shl 8 or (extra[1].toInt() and 0xff)).toLong()
            require(length >= 126) { "non-minimal WebSocket length" }
        } else if (lengthTag == 127) {
            val extra = readExact(input, 8) ?: return null
            require(extra[0].toInt() and 0x80 == 0) { "invalid WebSocket length sign bit" }
            length = extra.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) }
            require(length >= 65536) { "non-minimal WebSocket length" }
        }
        require(length <= MAX_MESSAGE_BYTES) { "WebSocket frame too large" }
        if (opcode >= OPCODE_CLOSE) require(final && length <= 125) { "invalid WebSocket control frame" }
        val mask = if (masked) readExact(input, 4) ?: return null else ByteArray(0)
        val data = if (length == 0L) ByteArray(0) else readExact(input, length.toInt()) ?: return null
        if (masked) {
            for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return Frame(opcode, data, final)
    }

    private fun writeLength(out: ByteArray, size: Int, masked: Boolean) {
        val maskBit = if (masked) 0x80 else 0
        when {
            size < 126 -> out[1] = (maskBit or size).toByte()
            size <= 0xffff -> {
                out[1] = (maskBit or 126).toByte()
                out[2] = ((size shr 8) and 0xff).toByte()
                out[3] = (size and 0xff).toByte()
            }
            else -> {
                out[1] = (maskBit or 127).toByte()
                var n = size.toLong()
                for (i in 9 downTo 2) {
                    out[i] = (n and 0xff).toByte()
                    n = n ushr 8
                }
            }
        }
    }

    private fun readExact(input: InputStream, count: Int): ByteArray? {
        val data = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(data, offset, count - offset)
            if (read < 0) return null
            offset += read
        }
        return data
    }
}

/** The Host bridge forwards complete messages, never raw continuation fragments. */
internal class DshWebSocketMessages(private val limit: Int = DshWebSocketFrames.MAX_MESSAGE_BYTES) {
    private var opcode: Int? = null
    private var fragments: ByteArrayOutputStream? = null

    fun accept(frame: DshWebSocketFrames.Frame): DshWebSocketFrames.Frame? {
        if (!DshWebSocketFrames.isData(frame.opcode)) return frame
        if (frame.opcode != DshWebSocketFrames.OPCODE_CONTINUATION) {
            require(opcode == null) { "new WebSocket message during fragmentation" }
            require(frame.payload.size <= limit) { "WebSocket message too large" }
            if (frame.final) return checked(frame)
            opcode = frame.opcode
            fragments = ByteArrayOutputStream().apply { write(frame.payload) }
            return null
        }
        val kind = requireNotNull(opcode) { "unexpected WebSocket continuation" }
        val buffer = requireNotNull(fragments)
        require(frame.payload.size <= limit - buffer.size()) { "WebSocket message too large" }
        buffer.write(frame.payload)
        if (!frame.final) return null
        val complete = DshWebSocketFrames.Frame(kind, buffer.toByteArray())
        opcode = null
        fragments = null
        return checked(complete)
    }

    private fun checked(frame: DshWebSocketFrames.Frame): DshWebSocketFrames.Frame {
        if (frame.opcode == DshWebSocketFrames.OPCODE_TEXT) {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(frame.payload))
        }
        return frame
    }
}
