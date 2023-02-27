@file:Suppress("unused")

package com.darkyen.cbor

import java.io.InputStream
import java.io.OutputStream

/**
 * Write into [ByteWrite] using APIs that expect [OutputStream] with this one weird trick!
 */
fun ByteWrite.outputStream(): OutputStream {
    return object : OutputStream() {
        override fun write(b: Int) {
            writeByte(b.toByte())
        }
        override fun write(b: ByteArray) {
            writeRaw(b, 0, b.size)
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            writeRaw(b, off, off + len)
        }
    }
}

/**
 * Wrapper around [InputStream] that turns it into a [ByteRead].
 */
class InputStreamByteRead(private val input: InputStream) : ChunkedByteRead() {
    override fun readChunk(intoBuffer: ByteArray, offset: Int, length: Int): Int {
        return input.read(intoBuffer, offset, length)
    }

    override fun skipChunk(length: Int): Int {
        if (length <= 0) return 0
        var remaining: Long = length.toLong()
        while (true) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                if (remaining <= 0) return length
            } else {
                break
            }
        }

        // Not skipped everything, either not enough data or skip not supported
        if (input.read() < 0) {
            // That was it.
            return length - remaining.toInt()
        }
        remaining -= 1

        // Skip is not supported or not reliable, fallback to reading
        val throwawayBuffer = ByteArray(1024)
        while (remaining > 0) {
            val read = input.read(throwawayBuffer)
            if (read < 0) {
                return length - remaining.toInt()
            }
            remaining -= read
        }
        return length
    }

    override fun suggestAvailableChunkBytes(): Int {
        val available = input.available()
        if (available <= 0 || available > 4096) {
            return 4096
        }
        return available
    }
}

/**
 * Wrapper around [OutputStream] that turns it into a [ByteWrite].
 */
class OutputStreamByteWrite(private val out: OutputStream) : ByteWrite {

    private val buffer = ByteArray(4096)
    private var bufferPos = 0

    override var totalWrittenBytes: Int = 0
        private set


    private fun writeRequire(amount: Int) {
        if (bufferPos + amount > buffer.size) {
            out.write(buffer, 0, bufferPos)
            bufferPos = 0
        }
    }

    override fun writeRawLE(value: Long, bytes: Int) {
        writeRequire(bytes)
        for (b in 0 until bytes) {
            buffer[bufferPos++] = (value ushr (b * 8)).toByte()
        }
    }

    override fun writeRawBE(value: Long, bytes: Int) {
        writeRequire(bytes)
        for (b in (bytes - 1) downTo 0) {
            buffer[bufferPos++] = (value ushr (b * 8)).toByte()
        }
    }

    override fun writeRaw(bytes: ByteArray, start: Int, end: Int) {
        val remaining = (end - start).coerceAtLeast(0)
        if (remaining >= buffer.size / 2) {
            // Direct write optimization
            flush()
            out.write(bytes, start, remaining)
            return
        }
        var offset = start
        while (offset < end) {
            val writeSize = (offset - end).coerceAtMost(buffer.size - bufferPos)
            bytes.copyInto(buffer, bufferPos, offset, offset + writeSize)
            bufferPos += writeSize
            offset += writeSize
            if (bufferPos >= buffer.size) {
                out.write(buffer, 0, bufferPos)
                bufferPos = 0
            }
        }
    }

    fun flush() {
        if (bufferPos > 0) {
            out.write(buffer, 0, bufferPos)
            bufferPos = 0
        }
    }

}