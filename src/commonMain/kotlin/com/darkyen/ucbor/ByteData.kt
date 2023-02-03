@file:Suppress("unused")

package com.darkyen.ucbor

import kotlin.math.max
import kotlin.math.min

/** Constant of an empty byte array. */
val EMPTY_BYTE_ARRAY = ByteArray(0)

/**
 * Stores a growing [ByteArray] with a read and write pointer
 * through which content can be read as primitives.
 *
 * Primitives are stored in little endian order.
 */
class ByteData : ByteRead, ByteWrite {
	private var data: ByteArray = EMPTY_BYTE_ARRAY
	private var nextRead = 0
	private var nextWrite = 0

	val internalBuffer: ByteArray
		get() = data
	val internalBufferBegin: Int
		get() = nextRead
	val internalBufferEnd: Int
		get() = nextWrite

	/** The amount of bytes that can be read, including those that were already read. */
	val size: Int
		get() = nextWrite

	override val totalWrittenBytes: Int
		get() = nextWrite

	/**
	 * Reset the [ByteData] to read given [data].
	 * The array is used directly as a buffer, so external modifications will be visible.
	 * Since writing would require a buffer expansion, the given array will not be modified by this class,
	 * unless [resetForWriting] with `reuseBuffer` = true is called.
	 */
	fun resetForReading(data: ByteArray, start: Int = 0, end: Int = data.size) {
		this.data = data
		this.nextRead = start
		this.nextWrite = end
	}

	/**
	 * Advanced function, don't use in conjunction with [rewindReading] or [toByteArray].
	 * When [data] is [ByteData], sets this as a view into that. Otherwise copies bytes.
	 */
	fun resetForReadingAndRead(data: ByteRead, length: Int) {
		if (!data.canRead(length)) {
			throw IllegalArgumentException("Argument does not have $length bytes to read")
		}
		if (data is ByteData) {
			this.data = data.data
			this.nextRead = data.nextRead
			this.nextWrite = data.nextRead + length
			data.nextRead += length
		} else {
			this.data = data.readBytes(length)!!
			this.nextRead = 0
			this.nextWrite = length
		}
	}

	/**
	 * Resets the content of this [ByteData] to be empty.
	 * @param reuseBuffer when true, the internal buffer is reused.
	 * This is preferred, but may not be desirable if the buffer was supplied through [resetForReading].
	 */
	fun resetForWriting(reuseBuffer: Boolean) {
		if (!reuseBuffer) {
			data = EMPTY_BYTE_ARRAY
		}
		nextRead = 0
		nextWrite = 0
	}

	/** Resets read head to the beginning. */
	fun rewindReading() {
		nextRead = 0
	}

	val availableBytes: Int
		get() = nextWrite - nextRead

	override fun suggestAvailableBytes(): Int = availableBytes

	override fun canRead(bytes: Int): Boolean = availableBytes >= bytes

	/** Checks and grows the back buffer as necessary to make sure that at least [bytes] can be written. */
	fun writeRequire(bytes: Int) {
		val requiredSize = nextWrite + bytes
		if (requiredSize <= data.size) {
			return
		}
		var newSize = max(data.size * 2, 16)
		while (newSize < requiredSize) {
			newSize *= 2
		}
		data = data.copyOf(newSize)
	}

	override fun readRawLE(bytes: Int): Long {
		if (!canRead(bytes)) throw IllegalStateException("Can't read $bytes bytes, only $availableBytes remaining")
		var result = 0L
		for (b in 0 until bytes) {
			result = result or ((data[nextRead++].toLong() and 0xFF) shl (b * 8))
		}
		return result
	}

	override fun readRawBE(bytes: Int): Long {
		if (!canRead(bytes)) throw IllegalStateException("Can't read $bytes bytes, only $availableBytes remaining")
		var result = 0L
		for (b in 0 until bytes) {
			result = (result shl 8) or (data[nextRead++].toLong() and 0xFF)
		}
		return result
	}

	override fun readRaw(bytes: ByteArray, start: Int, end: Int): Int {
		val len = (end - start).coerceAtLeast(0)
		val available = availableBytes
		val read = min(len, available)
		val endRead = nextRead + read
		data.copyInto(bytes, start, nextRead, endRead)
		nextRead = endRead
		return read
	}

	override fun writeRawLE(value: Long, bytes: Int) {
		writeRequire(bytes)
		for (b in 0 until bytes) {
			data[nextWrite++] = (value ushr (b * 8)).toByte()
		}
	}

	override fun writeRawBE(value: Long, bytes: Int) {
		writeRequire(bytes)
		for (b in (bytes - 1) downTo 0) {
			data[nextWrite++] = (value ushr (b * 8)).toByte()
		}
	}

	override fun writeRaw(bytes: ByteArray, start: Int, end: Int) {
		val len = end - start
		if (len <= 0) {
			return
		}
		writeRequire(len)
		val startWrite = nextWrite
		bytes.copyInto(data, startWrite, start, end)
		nextWrite = startWrite + len
	}

	// Optimized variants
	override fun readSkip(length: Int): Int {
		val skip = min(availableBytes, length)
		nextRead += skip
		return skip
	}

	override fun readUTF8(length: Int): String? {
		if (!canRead(length)) {
			return null
		}
		val result = data.decodeToString(nextRead, nextRead + length)
		nextRead += length
		return result
	}

	fun contentEquals(data: ByteArray): Boolean {
		val size = nextWrite
		if (data.size != size) {
			return false
		}
		val myData = this.data
		for (i in 0 until size) {
			if (myData[i] != data[i]) {
				return false
			}
		}
		return true
	}

	fun contentEquals(data: ByteData): Boolean {
		val size = nextWrite
		if (data.nextWrite != size) {
			return false
		}
		val myData = this.data
		val otherData = data.data
		for (i in 0 until size) {
			if (myData[i] != otherData[i]) {
				return false
			}
		}
		return true
	}

	/** Returns a copy of the contents (all that was written, including anything that was already read). */
	fun toByteArray(): ByteArray {
		if (nextWrite <= 0) {
			return EMPTY_BYTE_ARRAY
		}
		return data.copyOf(nextWrite)
	}
}

@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
@Retention(AnnotationRetention.SOURCE)
annotation class WriteDsl


@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
@Retention(AnnotationRetention.SOURCE)
annotation class ReadDsl

@WriteDsl
interface ByteWrite {

	val totalWrittenBytes: Int

	/** Write [bytes] least significant bytes of [value] in little endian order (least significant first). */
	fun writeRawLE(value: Long, bytes: Int)
	/** Write [bytes] least significant bytes of [value] in big endian order (most significant first). */
	fun writeRawBE(value: Long, bytes: Int)
	/** Write raw [bytes] */
	fun writeRaw(bytes: ByteArray, start: Int = 0, end: Int = bytes.size)

	fun writeByte(value: Byte) {
		writeRawLE(value.toLong(), 1)
	}

	fun writeShort(value: Short) {
		writeRawLE(value.toLong(), 2)
	}

	fun writeInt(value: Int) {
		writeRawLE(value.toLong(), 4)
	}

	fun writeFloat(value: Float) {
		writeRawLE(value.toRawBits().toLong(), 4)
	}

	fun writeLong(value: Long) {
		writeRawLE(value, 8)
	}

	fun writeDouble(value: Double) {
		writeRawLE(value.toRawBits(), 8)
	}

	fun writeString(value: String) {
		val bytes = value.encodeToByteArray()
		writeRawLE(bytes.size.toLong(), 2)
		writeRaw(bytes)
	}
}

@ReadDsl
interface ByteRead {

	/** Return true if it is possible to read at least [bytes] bytes. */
	fun canRead(bytes: Int): Boolean

	/** Return the amount of quickly available bytes. There can be more bytes available,
	 * but this should serve as a suggestion on how many bytes can be read right now, in bulk, efficiently. */
	fun suggestAvailableBytes(): Int

	/** Read [bytes] bytes in little endian order (least significant first) and return them in least significant bytes. */
	fun readRawLE(bytes: Int): Long
	/** Read [bytes] bytes in little endian order (most significant first) and return them in least significant bytes. */
	fun readRawBE(bytes: Int): Long
	/** Read raw [bytes]. Read how many bytes were actually read - reading less than requested implies that there are no more bytes. */
	fun readRaw(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): Int

	/** Skip at most [length] bytes, return how many bytes were skipped */
	fun readSkip(length: Int): Int

	fun readByteOr(defaultValue: Byte): Byte {
		if (!canRead(1)) return defaultValue
		return readRawLE(1).toByte()
	}

	fun readUnsignedByteOr(defaultValue: Int): Int {
		if (!canRead(1)) return defaultValue
		return readRawLE(1).toInt()
	}

	fun readShortOr(defaultValue: Short): Short {
		if (!canRead(2)) return defaultValue
		return readRawLE(2).toShort()
	}

	fun readIntOr(defaultValue: Int): Int {
		if (!canRead(4)) return defaultValue
		return readRawLE(4).toInt()
	}

	fun readFloatOr(defaultValue: Float): Float {
		if (!canRead(4)) return defaultValue
		return Float.fromBits(readRawLE(4).toInt())
	}

	fun readLongOr(defaultValue: Long): Long {
		if (!canRead(8)) return defaultValue
		return readRawLE(8)
	}

	fun readDoubleOr(defaultValue: Double): Double {
		if (!canRead(8)) return defaultValue
		return Double.fromBits(readRawLE(8))
	}

	fun readString(): String? {
		if (!canRead(2)) {
			return null
		}
		val length = readRawLE(2).toInt()
		return readUTF8(length)
	}

	fun readBytes(length: Int): ByteArray? {
		val result = ByteArray(length)
		if (readRaw(result) < length) {
			return null
		}
		return result
	}

	fun readUTF8(length: Int): String? {
		return readBytes(length)?.decodeToString()
	}

	fun readAllAvailableBytes(): ByteArray {
		val parts = ArrayList<ByteArray>()
		var lastPartSize = 0
		while (true) {
			var optimalChunkSize = suggestAvailableBytes()
			if (optimalChunkSize <= 0) {
				if (!canRead(1)) {
					// There really isn't anything else
					break
				}
				// There is more, suggestion is just not very good, but maybe it fixed itself after querying canRead?
				optimalChunkSize = suggestAvailableBytes()
				if (optimalChunkSize <= 0) {
					// No, suggest mechanism does not work, guess the chunk size
					optimalChunkSize = 512
				}
			}

			val part = ByteArray(optimalChunkSize)
			val actuallyRead = readRaw(part)
			if (actuallyRead < optimalChunkSize) {
				// Reading less than requested means that there is no more data
				if (actuallyRead > 0) {
					parts.add(part)
					lastPartSize = actuallyRead
				}
				break
			} else {
				parts.add(part)
				lastPartSize = part.size
			}
		}

		if (parts.size == 0 || (parts.size == 1 && lastPartSize == 0)) {
			return EMPTY_BYTE_ARRAY
		}
		if (parts.size == 1) {
			val lastPart = parts[0]
			if (lastPartSize != lastPart.size) {
				return lastPart.copyOf(lastPartSize)
			}
			return lastPart
		}
		var totalLength = lastPartSize
		for (i in 0 until parts.size - 1) {
			totalLength += parts[i].size
		}
		val combined = ByteArray(totalLength)
		var combinedOut = 0
		for (i in 0 until parts.size - 1) {
			val part = parts[i]
			part.copyInto(combined, combinedOut)
			combinedOut += part.size
		}
		parts[parts.lastIndex].copyInto(combined, combinedOut, 0, lastPartSize)
		return combined
	}
}

abstract class ChunkedByteRead : ByteRead {

	/** Fill more into provided [buffer], at provided [offset] and at most [length] bytes.
	 * @return amount of actually read bytes or -1 when there are no more bytes. */
	protected abstract fun readChunk(intoBuffer: ByteArray, offset: Int, length: Int): Int

	/** Behaves like [readChunk](ByteArray(length), 0, length), but does not have to read anything. */
	protected abstract fun skipChunk(length: Int): Int

	/** Return the amount of bytes left in the chunk for efficient bulk reading. */
	protected abstract fun suggestAvailableChunkBytes(): Int

	final override fun suggestAvailableBytes(): Int {
		val available = nextWrite - nextRead
		if (available > 0 || eof) {
			return available
		}
		return suggestAvailableChunkBytes()
	}

	private var buffer: ByteArray = EMPTY_BYTE_ARRAY
	private var nextRead: Int = 0
	private var nextWrite: Int = 0
	private var eof: Boolean = false

	open fun reset() {
		nextRead = 0
		nextWrite = 0
		eof = false
	}

	override fun canRead(bytes: Int): Boolean {
		val available = nextWrite - nextRead
		if (available >= bytes) {
			return true
		}
		if (eof) {
			return false
		}

		// Refill buffer
		if (buffer.size < bytes) {
			// Grow buffer
			val newBuffer = ByteArray(max(bytes, max(4096, buffer.size * 2)))
			buffer.copyInto(newBuffer, 0, nextRead, nextWrite)
			buffer = newBuffer
			nextWrite -= nextRead
			nextRead = 0
		} else if (buffer.size - nextRead < bytes) {
			// Compact buffer, because bytes would not fit the remaining space
			buffer.copyInto(buffer, 0, nextRead, nextWrite)
			nextWrite -= nextRead
			nextRead = 0
		} else if (nextRead == nextWrite) {
			// Compact buffer that is empty because it is cheap
			nextRead = 0
			nextWrite = 0
		}

		while (true) {
			val read = readChunk(buffer, nextWrite, buffer.size - nextWrite)
			if (read < 0) {
				eof = true
				break
			}
			nextWrite += read
			if (nextWrite - nextRead >= bytes) {
				return true
			}
		}

		return false
	}

	override fun readRawLE(bytes: Int): Long {
		if (!canRead(bytes)) throw IllegalStateException("Can't read $bytes bytes")
		var result = 0L
		for (b in 0 until bytes) {
			result = result or ((buffer[nextRead++].toLong() and 0xFF) shl (b * 8))
		}
		return result
	}

	override fun readRawBE(bytes: Int): Long {
		if (!canRead(bytes)) throw IllegalStateException("Can't read $bytes bytes")
		var result = 0L
		for (b in 0 until bytes) {
			result = (result shl 8) or (buffer[nextRead++].toLong() and 0xFF)
		}
		return result
	}

	override fun readRaw(bytes: ByteArray, start: Int, end: Int): Int {
		// Read from buffer
		val readLen = (end - start).coerceAtLeast(0)
		val availableInBuffer = nextWrite - nextRead
		val bufferCopyAmount = min(readLen, availableInBuffer)
		buffer.copyInto(bytes, start, nextRead, nextRead + bufferCopyAmount)
		nextRead += bufferCopyAmount

		// If there is still more remaining, the buffer is now empty so read chunks directly
		var outIndex = start + bufferCopyAmount
		var remaining = start + readLen - bufferCopyAmount

		while (remaining > 0 && !eof) {
			val read = readChunk(bytes, outIndex, remaining)
			if (read < 0) {
				// EOF
				eof = true
				break
			}
			outIndex += read
			remaining -= read
		}

		return start + readLen - remaining
	}

	override fun readSkip(length: Int): Int {
		var remaining = length.coerceAtLeast(0)
		var skipped = 0

		val skipFromBuffer = (nextWrite - nextRead).coerceAtMost(remaining)
		remaining -= skipFromBuffer
		skipped += skipFromBuffer
		nextRead += skipFromBuffer

		while (remaining > 0 && !eof) {
			val chunkSkipped = skipChunk(remaining)
			if (chunkSkipped < 0) {
				eof = true
				break
			}
			remaining -= chunkSkipped
			skipped += chunkSkipped
		}

		return skipped
	}

	override fun readUTF8(length: Int): String? {
		if (!canRead(length)) {
			return null
		}
		val result = buffer.decodeToString(nextRead, nextRead + length)
		nextRead += length
		return result
	}
}