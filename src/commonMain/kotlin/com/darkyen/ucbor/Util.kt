package com.darkyen.ucbor


typealias Codepoint = Int

inline fun CharSequence.forEachCodePoint(handle: (Codepoint) -> Unit) {
    val len = length
    var i = 0
    while (i < len) {
        val high = this[i]
        if (high.isHighSurrogate() && i + 1 < len) {
            val low = this[i + 1]
            if (low.isLowSurrogate()) {
                i += 2
                val cp = (high.code shl 10) + low.code - 0x35fdc00
                handle(cp)
                continue
            }
        }
        i += 1
        handle(high.code)
    }
}

fun Codepoint.utf8Bytes(): Int {
    return when {
        this <= 0x7F -> 1
        this <= 0x7FF -> 2
        this <= 0xFFFF -> 3
        else -> 4
    }
}

const val HEX_DIGITS = "0123456789ABCDEF"

fun StringBuilder.appendHexBytes(data: ByteArray, start: Int = 0, end: Int = data.size) {
    for (i in start until end) {
        val byte = data[i]
        append(HEX_DIGITS[(byte.toInt() ushr 4) and 0xF])
        append(HEX_DIGITS[byte.toInt() and 0xF])
    }
}

fun ByteArray.toHexString(maxBytes: Int = Int.MAX_VALUE): String {
    val s = size
    if (s <= maxBytes) {
        return buildString(s * 2) {
            appendHexBytes(this@toHexString)
        }
    } else {
        return buildString(maxBytes * 2 + 1) {
            appendHexBytes(this@toHexString)
            append('…')
        }
    }
}

/** Convert [Double] to [Float]. Same as [Double.toFloat] but actually works in JS backend.
 * See https://youtrack.jetbrains.com/issue/KT-24975/Enforce-range-of-Float-type-in-JS
 * and https://youtrack.jetbrains.com/issue/KT-35422/Fix-IntUIntDouble.toFloat-in-K-JS */
internal expect fun doubleToFloat(v: Double): Float