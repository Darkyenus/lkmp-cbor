package com.darkyen.cbor

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

// Utilities dealing with "raw JS strings" - strings which carry binary data in low 8-bits of each char
// These are used for example by atob/btoa JS methods which convert them to/from Base64

@JsName("String")
private external class JSString {
    companion object {
        val fromCharCode: dynamic
    }
}

// According to https://stackoverflow.com/questions/22747068/is-there-a-max-number-of-arguments-javascript-functions-can-accept
// this number should be safe.
private var JS_MAX_ARGUMENTS = 20000
private fun stringFromCharCodes(charcodeIterable: Uint8Array): String {
    val length = charcodeIterable.length
    if (length <= JS_MAX_ARGUMENTS) {
        return JSString.fromCharCode.apply(null, charcodeIterable).unsafeCast<String>()
    }
    // Split, because the array is too large and risks hitting JS engine argument limits
    val chunks = js("[]")
    var i = 0
    while (i < length) {
        val end = i + JS_MAX_ARGUMENTS
        val chunk = JSString.fromCharCode.apply(null, charcodeIterable.subarray(i, end))
        i = end
        chunks.push(chunk)
    }
    return chunks.join("").unsafeCast<String>()
}

private val ByteArray.underlyingArrayBuffer: ArrayBuffer
    get() = this.unsafeCast<Int8Array>().buffer

/**
 * Convert [bytes], an [Int8Array](https://kotlinlang.org/docs/js-to-kotlin-interop.html#primitive-arrays),
 * to a [String], whose codepoints correspond to the bytes.
 * The result has no textual meaning, the point is just to store bytes in string type.
 */
fun bytesToRawString(bytes: ByteArray): String {
    return stringFromCharCodes(Uint8Array(bytes.underlyingArrayBuffer))
}

/**
 * Convert [bytes], an [Int8Array](https://kotlinlang.org/docs/js-to-kotlin-interop.html#primitive-arrays),
 * from [offset] to [offset]+[length], to a [String], whose codepoints correspond to the bytes.
 * The result has no textual meaning, the point is just to store bytes in string type.
 */
fun bytesToRawString(bytes: ByteArray, offset: Int, length: Int): String {
    return stringFromCharCodes(Uint8Array(bytes.underlyingArrayBuffer, offset, length))
}

/** Convert bytes that are remaining to be read from [bytes] to a raw string.
 * Does not actually advance the read head. */
fun bytesToRawString(bytes: ByteData): String {
    return stringFromCharCodes(
        Uint8Array(
            bytes.internalBuffer.underlyingArrayBuffer,
            bytes.internalBufferBegin,
            bytes.internalBufferEnd - bytes.internalBufferBegin
        )
    )
}

/** Reverse operation to [bytesToRawString]. */
fun rawStringToBytes(string: String): ByteArray {
    val len = string.length
    if (len <= 0) {
        return EMPTY_BYTE_ARRAY
    }
    return ByteArray(len) {
        string.get(it).code.toByte()
    }
}

fun rawStringToBytes(string: String, offset: Int = 0, length: Int = string.length - offset): ByteArray {
    if (length <= 0) {
        return EMPTY_BYTE_ARRAY
    }
    return ByteArray(length) {
        string.get(offset + it).code.toByte()
    }
}

fun rawStringToBytes(string: String, out: ByteData, offset: Int = 0, length: Int = string.length - offset) {
    for (i in 0 until length) {
        out.writeByte(string.get(offset + i).code.toByte())
    }
}

inline fun serializeCborToRawString(block: CborWrite.() -> Unit): String {
    val data = ByteData()
    val write = CborWrite(data)
    write.block()
    return bytesToRawString(data)
}

inline fun <T> deserializeCborFromRawString(rawString: String, block: CborRead.() -> T): T {
    val bytes = rawStringToBytes(rawString)
    val data = ByteData()
    data.resetForReading(bytes)
    val read = CborRead(data)
    return read.block()
}