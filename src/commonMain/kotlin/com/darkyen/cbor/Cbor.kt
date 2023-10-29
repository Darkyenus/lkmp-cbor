@file:Suppress("unused", "FunctionName", "PropertyName", "LiftReturnOrAssignment", "MemberVisibilityCanBePrivate")

package com.darkyen.cbor

import kotlin.jvm.JvmInline
import kotlin.math.max
import kotlin.math.min

/**
 * Allows to read from [ByteRead].
 * Instances are not thread safe.
 */
@ReadDsl
class CborRead(
    @PublishedApi
    internal val _br: ByteRead
    ) {

    // Fields and methods that are underscore prefixed are internal published API, but should be treated as private.

    enum class CborValueType {
        /** Pseudo-type, means that there are no more values. */
        END,
        /** An integer (64-bit) */
        INT,
        BLOB,
        TEXT,
        ARRAY,
        MAP,
        TAG,
        BOOLEAN,
        NULL,
        UNDEFINED,
        FLOAT16,
        FLOAT32,
        FLOAT64,
        ;

        val float: Boolean
            get() = this == FLOAT16 || this == FLOAT32 || this == FLOAT64
    }

    @PublishedApi
    internal var _headerType: CborValueType = CborValueType.END
    @PublishedApi
    internal var _headerArgument: Long = 0
    @PublishedApi
    internal var _payloadRemaining: Int = REMAINING_SEQUENCE

    /** [_payloadRemaining] exposed for debugger */
    internal val debugPayloadRemaining: String
        get() = when (_payloadRemaining) {
            REMAINING_SEQUENCE -> "SEQ: Expecting value or EOF"
            REMAINING_BLOB_CHUNKS -> "BLO: Expecting blob chunks or END"
            REMAINING_STRING_CHUNKS -> "STR: Expecting string chunks or END"
            REMAINING_INDEFINITE_LIST -> "LST: Expecting value or END"
            REMAINING_INDEFINITE_MAP_NEXT_KEY -> "MPK: Expecting key value or END"
            REMAINING_INDEFINITE_MAP_NEXT_VALUE -> "MPV: Expecting map value"
            REMAINING_BREAK -> "BRK: Got END, waiting for nesting end"
            else -> "Expecting $_payloadRemaining more values"
        }

    /**
     * Two part value. High 32 bits specify state [FIELD_NONE], [FIELD_CONSUMED], [FIELD_PEEKED], [FIELD_END],
     * low 32 bits specify field name.
     */
    @PublishedApi
    internal var _fieldProgress: Long = FIELD_NONE

    /** [_fieldProgress] exposed for debugger */
    internal val debugFieldProgress: String
        get() = when (val high = _fieldProgress and FIELD_MASK) {
            FIELD_NONE -> "No field"
            FIELD_CONSUMED -> "Consumed ${_fieldProgress and 0xFFFF_FFFFL}"
            FIELD_PEEKED -> "Peeked ${_fieldProgress and 0xFFFF_FFFFL}"
            FIELD_END -> "Reached obj end"
            else -> "Invalid ($high:${_fieldProgress and 0xFFFF_FFFFL})"
        }

    @Throws(CborDecodeException::class)
    @PublishedApi
    internal fun _readHeader(): CborValueType {
        val contextPayloadRemaining = _payloadRemaining
        this._headerType = CborValueType.END
        this._headerArgument = 0
        this._payloadRemaining = 0

        val canReadMorePayload = when (contextPayloadRemaining) {
            0 -> false
            REMAINING_SEQUENCE -> true
            REMAINING_BLOB_CHUNKS -> true
            REMAINING_STRING_CHUNKS -> true
            REMAINING_INDEFINITE_LIST -> true
            REMAINING_INDEFINITE_MAP_NEXT_KEY -> true
            REMAINING_INDEFINITE_MAP_NEXT_VALUE -> true
            REMAINING_BREAK -> false // Should not happen
            REMAINING_ERROR -> throw CborDecodeError("Can't read more after error")
            else -> if (contextPayloadRemaining > 0) {
                true
            } else throw CborDecodeError("Unexpected payloadRemaining: $_payloadRemaining")
        }

        if (!canReadMorePayload) {
            return CborValueType.END
        }

        if (!_br.canRead(1)) {
            // EOF
            val eofLegal = when (contextPayloadRemaining) {
                 0, REMAINING_SEQUENCE -> true
                else -> false
            }
            if (eofLegal) {
                return CborValueType.END
            }
            throw CborDecodeException("Expected CBOR value, got EOF")
        }

        val head = _br.readRawBE(1).toInt()
        val major = head and CborConstants.MAJOR_MASK
        val minor = head and CborConstants.MINOR_MASK

        val value: Long = when {
            minor < 24 -> minor.toLong()
            minor == 24 -> if (_br.canRead(1)) {
                _br.readRawBE(1)
            } else {
                throw CborDecodeException("argument", 1, "bytes")
            }

            minor == 25 -> if (_br.canRead(2)) {
                _br.readRawBE(2)
            } else {
                throw CborDecodeException("argument", 2, "bytes")
            }

            minor == 26 -> if (_br.canRead(4)) {
                _br.readRawBE(4)
            } else {
                throw CborDecodeException("argument", 4, "bytes")
            }

            minor == 27 -> if (_br.canRead(8)) {
                _br.readRawBE(8)
            } else {
                throw CborDecodeException("argument", 8, "bytes")
            }

            minor in 28..30 -> throw CborDecodeException("Got reserved argument value $minor")

            else -> {
                // Indefinite
                val expectBreak = when (contextPayloadRemaining) {
                    REMAINING_BLOB_CHUNKS,
                    REMAINING_STRING_CHUNKS,
                    REMAINING_INDEFINITE_LIST,
                    REMAINING_INDEFINITE_MAP_NEXT_KEY -> true
                    else -> false
                }
                if (major == CborConstants.MAJOR_7_OTHER) {
                    // Break
                    if (expectBreak) {
                        _payloadRemaining = REMAINING_BREAK
                        return CborValueType.END
                    } else {
                        throw CborDecodeException("Unexpected break")
                    }
                }
                if (major !in CborConstants.MAJOR_2_BLOB..CborConstants.MAJOR_5_MAP) {
                    throw CborDecodeException("Indefinite argument not supported on major type ${major ushr 5}")
                }
                val allowIndefinite = when (contextPayloadRemaining) {
                    REMAINING_BLOB_CHUNKS -> false
                    REMAINING_STRING_CHUNKS -> false
                    else -> true
                }
                if (!allowIndefinite) {
                    throw CborDecodeException("Unexpected indefinite argument of major type ${major ushr 5}")
                }
                0L
            }
        }
        this._headerArgument = value

        if (contextPayloadRemaining == REMAINING_BLOB_CHUNKS && major != CborConstants.MAJOR_2_BLOB) {
            throw CborDecodeException("Unexpected major ${major ushr 5} when expecting blob chunks")
        }
        if (contextPayloadRemaining == REMAINING_STRING_CHUNKS && major != CborConstants.MAJOR_3_STRING) {
            throw CborDecodeException("Unexpected major ${major ushr 5} when expecting string chunks")
        }

        val type = when (major) {
            CborConstants.MAJOR_0_UINT -> CborValueType.INT
            CborConstants.MAJOR_1_NINT -> {
                this._headerArgument = -value - 1
                CborValueType.INT
            }
            CborConstants.MAJOR_2_BLOB -> {
                if (minor == CborConstants.MINOR_INDEFINITE) {
                    _payloadRemaining = REMAINING_BLOB_CHUNKS
                } else if (value in 0..Int.MAX_VALUE && _br.canRead(value.toInt())) {
                    _payloadRemaining = value.toInt()
                } else {
                    throw CborDecodeException("blob payload", value, "bytes")
                }
                CborValueType.BLOB
            }
            CborConstants.MAJOR_3_STRING -> {
                if (minor == CborConstants.MINOR_INDEFINITE) {
                    _payloadRemaining = REMAINING_STRING_CHUNKS
                } else if (value in 0..Int.MAX_VALUE && _br.canRead(value.toInt())) {
                    _payloadRemaining = value.toInt()
                } else {
                    throw CborDecodeException("string payload", value, "bytes")
                }
                CborValueType.TEXT
            }
            CborConstants.MAJOR_4_ARRAY -> {
                if (minor == CborConstants.MINOR_INDEFINITE) {
                    _payloadRemaining = REMAINING_INDEFINITE_LIST
                } else if (value in 0..Int.MAX_VALUE) {
                    _payloadRemaining = value.toInt()
                } else {
                    throw CborDecodeException("array payload is too large ($value)")
                }
                CborValueType.ARRAY
            }
            CborConstants.MAJOR_5_MAP -> {
                if (minor == CborConstants.MINOR_INDEFINITE) {
                    _payloadRemaining = REMAINING_INDEFINITE_MAP_NEXT_KEY
                } else if (value in 0..Int.MAX_VALUE/2) {
                    _payloadRemaining = value.toInt() * 2
                } else {
                    throw CborDecodeException("map payload is too large ($value)")
                }
                CborValueType.MAP
            }
            CborConstants.MAJOR_6_TAG -> {
                _payloadRemaining = 1
                CborValueType.TAG
            }
            CborConstants.MAJOR_7_OTHER -> when (minor) {
                20 -> {
                    this._headerArgument = 0
                    CborValueType.BOOLEAN
                }
                21 -> {
                    this._headerArgument = 1
                    CborValueType.BOOLEAN
                }
                22 -> CborValueType.NULL
                23 -> CborValueType.UNDEFINED
                25 -> CborValueType.FLOAT16
                26 -> CborValueType.FLOAT32
                27 -> CborValueType.FLOAT64
                else -> throw CborDecodeException("Unsupported minor $minor of major 7")
            }
            else -> throw CborDecodeError("Unsupported major $major")
        }
        this._headerType = type
        return type
    }

    @PublishedApi
    internal fun _expectType(expectType: CborValueType) {
        if (_headerType != expectType) {
            throw CborDecodeException("Expected $expectType, got $_headerType")
        }
    }

    @Throws(CborDecodeException::class)
    inline fun <T> read(handle: CborReadSingle.(type: CborValueType) -> T): T {
        val remainingBefore = _payloadRemaining
        val header = _readHeader()
        try {
            return handle(CborReadSingle(this), header)
        } catch(e: Throwable) {
            _payloadRemaining = REMAINING_ERROR
            throw e
        } finally {
            _readValueEnd(remainingBefore)
        }
    }

    @PublishedApi
    internal fun _readValueEnd(outerPayloadRemainingBefore: Int) {
        // Check that the sequence was read correctly
        if (_payloadRemaining == REMAINING_BREAK) {
            // Got break, that means that outer payload is done
            _payloadRemaining = 0
            return
        }

        // After reading value, payloadRemaining must always be 0
        if (_payloadRemaining != 0) {
            // There is probably a pending error already
            if (_payloadRemaining == REMAINING_ERROR) return
            throw CborDecodeException("After reading a value, the remaining payload is not 0 but $_payloadRemaining")
        }

        _payloadRemaining = when {
            outerPayloadRemainingBefore == REMAINING_INDEFINITE_MAP_NEXT_KEY -> REMAINING_INDEFINITE_MAP_NEXT_VALUE
            outerPayloadRemainingBefore == REMAINING_INDEFINITE_MAP_NEXT_VALUE -> REMAINING_INDEFINITE_MAP_NEXT_KEY
            outerPayloadRemainingBefore > 0 -> outerPayloadRemainingBefore - 1
            else -> outerPayloadRemainingBefore
        }
    }

    /** @see CborReadSingle.value */
    @Throws(CborDecodeException::class)
    fun value(): CborValue? {
        return read { value() }
    }

    @Throws(CborDecodeException::class)
    fun skipValue(): Boolean {
        return read { skip() }
    }

    @PublishedApi
    @Throws(CborDecodeException::class)
    internal fun skipExpectedValue() {
        val skipped = read { skip() }
        if (!skipped) {
            throw CborDecodeError("Expected value to skip")
        }
    }

    /**
     * Obtained from [CborRead.read] and allows to read exactly one value of the given type.
     */
    @ReadDsl
    @JvmInline
    value class CborReadSingle(@PublishedApi internal val _cr: CborRead) {

        val type: CborValueType
            get() = _cr._headerType

        /**
         * Read the next CBOR value which is an int.
         * @throws CborDecodeException when the value is not an int or is invalid
         */
        @Throws(CborDecodeException::class)
        fun int(): Long {
            _cr._expectType(CborValueType.INT)
            return _cr._headerArgument
        }

        /**
         * Read the next CBOR value which is an int and clamps its value to 32 bits.
         * @throws CborDecodeException when the value is not an int or is invalid
         */
        @Throws(CborDecodeException::class)
        fun int32(): Int {
            _cr._expectType(CborValueType.INT)
            return _cr._headerArgument.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        }

        /**
         * Read the next CBOR value which is a boolean.
         * @throws CborDecodeException when the value is not a boolean or is invalid
         */
        @Throws(CborDecodeException::class)
        fun boolean(): Boolean {
            _cr._expectType(CborValueType.BOOLEAN)
            return _cr._headerArgument == 1L
        }

        /**
         * Read the next CBOR value which is a null.
         * @throws CborDecodeException when the value is not a null or is invalid
         */
        @Throws(CborDecodeException::class)
        fun `null`(): Nothing? {
            _cr._expectType(CborValueType.NULL)
            return null
        }

        /**
         * Read the next CBOR value which is an undefined.
         * @throws CborDecodeException when the value is not an undefined or is invalid
         */
        @Throws(CborDecodeException::class)
        fun undefined() {
            _cr._expectType(CborValueType.UNDEFINED)
        }

        /**
         * Read the next CBOR value which is a floating point number.
         * @throws CborDecodeException when the value is not a float or is invalid
         */
        @Throws(CborDecodeException::class)
        fun float(): Double {
            val value = _cr._headerArgument
            return when (val type = _cr._headerType) {
                CborValueType.FLOAT16 -> {
                    // float16
                    Half(value.toInt()).toFloat().toDouble()
                }

                CborValueType.FLOAT32 -> {
                    // float32
                    Float.fromBits(value.toInt()).toDouble()
                }

                CborValueType.FLOAT64 -> {
                    // float64
                    Double.fromBits(value)
                }

                else -> throw CborDecodeException("Expected FLOAT, got $type")
            }
        }

        /**
         * Read the next CBOR value which is a tagged value.
         * @throws CborDecodeException when the value is not a tagged value or is invalid or the tag does not match [expectedTag]
         */
        @Throws(CborDecodeException::class)
        inline fun <T> tag(expectedTag: Long = ANY_TAG, readTagged: CborRead.(tag: Long) -> T): T {
            _cr._expectType(CborValueType.TAG)
            val actualTag = _cr._headerArgument
            if (expectedTag != ANY_TAG && expectedTag != actualTag) {
                throw CborDecodeException("Expected tag $expectedTag, got $actualTag")
            }
            return readTagged(_cr, actualTag)
        }

        /**
         * Read the next CBOR value which is a blob (byte array), possibly indeterminately encoded.
         * @throws CborDecodeException when the value is not a blob or is invalid or the length is not [expectedLength]
         */
        @Throws(CborDecodeException::class)
        fun blob(expectedLength: Int = ANY): ByteArray {
            return blob(expectedLength) {
                if (expectedLength == ANY) {
                    // Unknown length
                    readAllAvailableBytes()
                } else {
                    // Known length
                    readBytes(expectedLength)!!
                }
            }
        }

        /**
         * Read the next CBOR value which is a blob (byte array), possibly indeterminately encoded.
         * @param read callback to read the entire (!) content
         * @throws CborDecodeException when the value is not a blob or is invalid or its byte length is not [expectedLength]
         */
        @Throws(CborDecodeException::class)
        inline fun <T> blob(expectedLength: Int = ANY, read: ByteRead.() -> T): T {
            _cr._expectType(CborValueType.BLOB)
            val byteRead = _cr._blobBegin(expectedLength)
            val result = read(byteRead)
            _cr._blobEnd(byteRead, expectedLength)
            return result
        }

        /**
         * Read the next CBOR value which is a [CharSequence].
         * @throws CborDecodeException when the value is not a string or is an invalid value
         */
        @Throws(CborDecodeException::class)
        fun string(): CharSequence {
            _cr._expectType(CborValueType.TEXT)
            return if (_cr._payloadRemaining == REMAINING_STRING_CHUNKS) {
                // Indefinite length
                val parts = StringBuilder()
                while (true) {
                    parts.append(_cr.read { type ->
                        if (type == CborValueType.END) {
                            null
                        } else {
                            val s = _cr._br.readUTF8(_cr._payloadRemaining)!!
                            _cr._payloadRemaining = 0
                            s
                        }
                    } ?: break)
                }
                parts
            } else {
                // Definite length
                val result = _cr._br.readUTF8(_cr._payloadRemaining)!!
                _cr._payloadRemaining = 0
                result
            }
        }

        /**
         * Read the next CBOR value which is an array.
         * Correct usage of this method is a bit tricky, when possible use [arrayInto] instead.
         *
         * @param read must read values until all are read
         * @throws CborDecodeException when the value is not an array or is an invalid value or [read] performs illegal calls (too many or not enough reads)
         */
        @Throws(CborDecodeException::class)
        inline fun <T> arrayRaw(read: CborRead.(countHint: Int) -> T): T {
            _cr._expectType(CborValueType.ARRAY)
            return read(_cr, _cr._headerArgument.toInt())
        }

        /**
         * Read the next CBOR value which is an array.
         * @param read must read values until all are read
         * @return [out], for convenience
         * @throws CborDecodeException when the value is not an array or is an invalid value or [read] performs illegal calls (too many or not enough reads)
         */
        @Throws(CborDecodeException::class)
        inline fun <T, C:MutableCollection<T>> arrayInto(out: C, read: CborDeserialize<T>): C {
            arrayRaw { countHint ->
                if (countHint > 0 && out is ArrayList<*>) {
                    out.ensureCapacity(countHint)
                }
                do {
                    val hasMore = read { type ->
                        if (type == CborValueType.END) {
                            false
                        } else {
                            out.add(with(read) { deserialize() })
                            true
                        }
                    }
                } while (hasMore)
            }
            return out
        }

        /**
         * Read the next CBOR value which is a map.
         * @param read must read values until all are read. Note that value must be read for each key AND value.
         * @throws CborDecodeException when the value is not an array or is an invalid value or [read] performs illegal calls (too many or not enough reads)
         */
        @Throws(CborDecodeException::class)
        inline fun <T> mapRaw(read: CborRead.(pairCountHint: Int) -> T): T {
            _cr._expectType(CborValueType.MAP)
            return read(_cr, _cr._headerArgument.toInt())
        }

        inline fun <M:MutableMap<K, V>, K, V> map(newMap:(capacity: Int) -> M, readKey: CborDeserialize<K>, readValue: CborDeserialize<V>): M {
            return mapRaw m@{ size ->
                val result = newMap(size + size/2)
                while (true) {
                    val k = read { type ->
                        if (type == CborValueType.END) {
                            return@m result
                        }
                        with(readKey) { deserialize() }
                    }
                    val v = read { with(readValue) { deserialize() } }
                    result[k] = v
                }
                @Suppress("UNREACHABLE_CODE")// Provides type inference
                result
            }
        }

        inline fun <K, V> mapInto(out:MutableMap<K, V>, readKey: CborDeserialize<K>, readValue: CborDeserialize<V>) {
            return mapRaw m@{
                while (true) {
                    val k = read { type ->
                        if (type == CborValueType.END) {
                            return@m
                        }
                        value(readKey)
                    }
                    val v = value(readValue)
                    out[k] = v
                }
            }
        }

        /**
         * Read the next CBOR value which is a [T] serialized by [serializer].
         * @throws CborDecodeException when the value is not a [T] or is an invalid value
         */
        @Throws(CborDecodeException::class)
        inline fun <T> value(serializer: CborDeserialize<T>):T {
            return with(serializer) {
                deserialize()
            }
        }

        /**
         *  Read a single value in generic format.
         * @return the value or null if there are no more values in this context
         * (this is allowed only when the context allows it, it will never happen when not allowed by the specification)
         */
        @Throws(CborDecodeException::class)
        fun value(): CborValue? {
            return when (_cr._headerType) {
                CborValueType.END -> null
                CborValueType.INT -> CborValue.Int(_cr._headerArgument)
                CborValueType.BLOB -> CborValue.Blob(blob())
                CborValueType.TEXT -> CborValue.Text(string().toString())
                CborValueType.ARRAY -> CborValue.Array(arrayRaw {
                    val result = ArrayList<CborValue>(_payloadRemaining.let { if (it < 0) 10 else it })
                    while (true) {
                        result.add(value() ?: break)
                    }
                    result
                })
                CborValueType.MAP -> CborValue.Map(mapRaw {
                    val result = ArrayList<Pair<CborValue, CborValue>>(_payloadRemaining.let { if (it < 0) 10 else it })
                    while (true) {
                        val key = value() ?: break
                        val value = value()?: throw CborDecodeError("Unexpected EOF reading MAP key")
                        result.add(key to value)
                    }
                    result
                })
                CborValueType.TAG -> tag { t ->
                    CborValue.Tag(t, value()!!/* can't be null, checked by readHeader */)
                }
                CborValueType.BOOLEAN -> if (_cr._headerArgument == 1L) CborValue.True else CborValue.False
                CborValueType.NULL -> CborValue.Null
                CborValueType.UNDEFINED -> CborValue.Undefined
                CborValueType.FLOAT16 -> CborValue.Float(float(), 2)
                CborValueType.FLOAT32 -> CborValue.Float(float(), 4)
                CborValueType.FLOAT64 -> CborValue.Float(float(), 8)
            }
        }

        /**
         * Skip a value, as if reading through [value].
         * @return true if skipped, false if there was no value to skip
         */
        @Throws(CborDecodeException::class)
        fun skip(): Boolean {
            return when (_cr._headerType) {
                CborValueType.END -> false
                CborValueType.BOOLEAN,
                CborValueType.NULL,
                CborValueType.UNDEFINED,
                CborValueType.INT,
                CborValueType.FLOAT16,
                CborValueType.FLOAT32,
                CborValueType.FLOAT64 -> true// Done
                CborValueType.BLOB,
                CborValueType.TEXT -> {
                    if (_cr._payloadRemaining == REMAINING_STRING_CHUNKS || _cr._payloadRemaining == REMAINING_BLOB_CHUNKS) {
                        while(_cr.read {
                                if (it == CborValueType.END) {
                                    false
                                } else {
                                    _cr._br.readSkip(_cr._headerArgument.toInt())
                                    _cr._payloadRemaining = 0
                                    true
                                }
                            }) {
                            // All work in condition
                        }
                    } else {
                        if (_cr._payloadRemaining < 0) throw CborDecodeError("Expected non-negative payloadRemaining: ${_cr._payloadRemaining}")
                        _cr._br.readSkip(_cr._payloadRemaining)
                        _cr._payloadRemaining = 0
                    }
                    true
                }
                CborValueType.ARRAY -> {
                    @Suppress("ControlFlowWithEmptyBody")
                    while (_cr.skipValue()) {}
                    true
                }
                CborValueType.MAP -> {
                    while (_cr.skipValue()) {
                        _cr.skipExpectedValue()
                    }
                    true
                }
                CborValueType.TAG -> {
                    _cr.skipExpectedValue()
                    true
                }
            }
        }

        /**
         * Read object with fields. See [CborWrite.obj] for counterpart.
         */
        inline fun <T> obj(read: CborReadFields.() -> T):T {
            return mapRaw {
                val oldFieldProgress = this@CborReadSingle._cr._fieldProgress
                this@CborReadSingle._cr._fieldProgress = FIELD_NONE
                try {
                    val crf = CborReadFields(this@CborReadSingle._cr)
                    val result = read(crf)
                    crf._skipRemainingFields()
                    result
                } finally {
                    this@CborReadSingle._cr._fieldProgress = oldFieldProgress
                }
            }
        }
    }

    @JvmInline
    value class CborReadFields(@PublishedApi internal val _cr: CborRead) {

        @PublishedApi
        internal fun fieldInternal(fieldId: Int): Boolean {
            val progress = _cr._fieldProgress
            val progressStatus = progress and FIELD_MASK
            val progressField = progress.toInt()
            when (progressStatus) {
                FIELD_NONE -> {}
                FIELD_CONSUMED -> {
                    if (progressField >= fieldId) {
                        throw CborDecodeError("Fields must be requested in strictly increasing order, but requesting $fieldId after $progressField")
                    }
                }
                FIELD_PEEKED -> {
                    if (progressField > fieldId) {
                        // Already read a higher field, this field can't be present
                        return false
                    }
                    if (progressField == fieldId) {
                        _cr._fieldProgress = FIELD_CONSUMED or (progressField.toLong() and 0xFFFF_FFFFL)
                        return true
                    }
                    // progressField < fieldId
                    _cr.skipExpectedValue()
                }
                FIELD_END -> {
                    // Nothing found
                    return false
                }
                else -> throw CborDecodeError("Unexpected field progress $progress")
            }

            while (true) {
                val nextField = _cr.read {
                    if (it == CborValueType.END) {
                        _cr._fieldProgress = FIELD_END
                        return false
                    }
                    int()
                }
                if (nextField < fieldId) {
                    // Will skip while nextField < Int.MIN_VALUE
                    _cr.skipExpectedValue()
                    continue
                } else if (nextField == fieldId.toLong()) {
                    _cr._fieldProgress = FIELD_CONSUMED or (fieldId.toLong() and 0xFFFF_FFFFL)
                    return true
                } else {
                    // nextField > fieldId
                    if (nextField > Int.MAX_VALUE) {
                        // Skip until the end
                        _cr.skipExpectedValue()
                        while (_cr.skipValue()) {
                            _cr.skipExpectedValue()
                        }

                        _cr._fieldProgress = FIELD_END
                    } else {
                        _cr._fieldProgress = FIELD_PEEKED or (nextField and 0xFFFF_FFFFL)
                    }
                    // There is no such field
                    return false
                }
            }
        }

        @PublishedApi
        internal fun _skipRemainingFields() {
            when (_cr._fieldProgress and FIELD_MASK) {
                FIELD_NONE,
                FIELD_CONSUMED -> {}
                FIELD_PEEKED -> _cr.skipExpectedValue()
                FIELD_END -> return
            }
            while (_cr.skipValue()) {
                _cr.skipExpectedValue()
            }
            _cr._fieldProgress = FIELD_END
        }

        inline fun <T> field(fieldId: Int, read: CborReadSingle.(CborValueType) -> T, fieldMissing: () -> T): T {
            return if (fieldInternal(fieldId)) {
                _cr.read(read)
            } else {
                fieldMissing()
            }
        }

        inline fun <T> field(fieldId: Int, read: CborReadSingle.(CborValueType) -> T): T {
            return field(fieldId, read) { throw CborDecodeException("Required field $fieldId is missing") }
        }

        inline fun <T : TD, TD> fieldOr(fieldId: Int, default:TD, read: CborReadSingle.(CborValueType) -> T): TD {
            return field(fieldId, read) { default }
        }

        fun <T> field(fieldId: Int, serializer: CborDeserialize<T>): T {
            return field(fieldId) { value(serializer) }
        }

        fun <T : TD, TD> fieldOr(fieldId: Int, default:TD, serializer: CborDeserialize<T>): TD {
            return field(fieldId, { value(serializer) }, { default })
        }

        fun <T> fieldOrNull(fieldId: Int, serializer: CborDeserialize<T>): T? {
            return field(fieldId, {
                if (type == CborValueType.NULL) {
                    null
                } else {
                    value(serializer)
                }
            }, { null })
        }

        fun fieldInt32OrZero(fieldId: Int): Int {
            return field(fieldId, { if (type == CborValueType.INT) int32() else 0 }, { 0 })
        }

        fun fieldIntOrZero(fieldId: Int): Long {
            return field(fieldId, { if (type == CborValueType.INT) int() else 0L }, { 0L })
        }

        fun fieldFloat32OrZero(fieldId: Int): Float {
            return field(fieldId, { if (type.float) doubleToFloat(float()) else 0f }, { 0f })
        }

        fun fieldFloatOrZero(fieldId: Int): Double {
            return field(fieldId, { if (type.float) float() else 0.0 }, { 0.0 })
        }

        fun fieldString(fieldId: Int): String {
            return field(fieldId) { string().toString() }
        }
        fun fieldStringOrNull(fieldId: Int): String? {
            return field(fieldId, { if (type == CborValueType.TEXT) string().toString() else null }, { null })
        }
        fun fieldBlob(fieldId: Int): ByteArray {
            return field(fieldId) { blob() }
        }
        fun fieldBlobOrNull(fieldId: Int): ByteArray? {
            return field(fieldId, { if (type == CborValueType.BLOB) blob() else null }, { null })
        }
    }

    private val solidBlobRead = ByteData()
    private val chunkedBlobRead = object : ChunkedByteRead() {

        private var bytesRemaining: Int = 0
        var totalBytes: Int = 0

        override fun reset() {
            super.reset()
            bytesRemaining = 0
            totalBytes = 0
        }

        private fun ensureInChunk() {
            val thisCBR = this
            while (bytesRemaining == 0) {
                this@CborRead.read { type ->
                    when (type) {
                        CborValueType.END -> thisCBR.bytesRemaining = -1
                        CborValueType.BLOB -> {
                            val chunkSize = this@CborRead._payloadRemaining
                            if (chunkSize < 0) throw CborDecodeError("Expected non-negative blob chunk payload, got ${this@CborRead._payloadRemaining}")
                            this@CborRead._payloadRemaining = 0// Consume eagerly for readValue to be happy, but don't actually read it now
                            thisCBR.bytesRemaining = chunkSize
                            thisCBR.totalBytes += chunkSize
                        }
                        else -> throw CborDecodeError("Expected BLOB, got $type")
                    }
                }
            }
        }

        override fun readChunk(intoBuffer: ByteArray, offset: Int, length: Int): Int {
            ensureInChunk()
            if (bytesRemaining < 0) {
                return -1
            }
            val expectRead = min(bytesRemaining, length)
            val actuallyRead = this@CborRead._br.readRaw(intoBuffer, offset, offset + expectRead)
            bytesRemaining -= actuallyRead
            if (actuallyRead != expectRead) throw CborDecodeError("Expected to read $expectRead blob chunk data, but read only $actuallyRead")
            return expectRead
        }

        override fun skipChunk(length: Int): Int {
            ensureInChunk()
            if (bytesRemaining < 0) {
                return -1
            }
            val expectRead = min(bytesRemaining, length)
            val actuallyRead = this@CborRead._br.readSkip(expectRead)
            bytesRemaining -= actuallyRead
            if (actuallyRead != expectRead) throw CborDecodeError("Expected to skip $expectRead blob chunk data, but skipped only $actuallyRead")
            return expectRead
        }

        override fun suggestAvailableChunkBytes(): Int {
            ensureInChunk()
            return bytesRemaining.coerceAtLeast(0)
        }
    }

    @PublishedApi
    internal fun _blobBegin(expectedLength: Int): ByteRead {
        if (_payloadRemaining == REMAINING_BLOB_CHUNKS) {
            // Indefinite
            val chunkedRead = chunkedBlobRead
            chunkedRead.reset()
            return chunkedRead
        } else {
            // Definite
            if (expectedLength != ANY && expectedLength != _payloadRemaining) {
                throw CborDecodeException("Expected blob length $expectedLength, got $_payloadRemaining")
            }
            val data = solidBlobRead
            data.resetForReadingAndRead(this._br, _payloadRemaining)
            _payloadRemaining = 0
            return data
        }
    }

    @PublishedApi
    internal fun _blobEnd(byteRead: ByteRead, expectedLength: Int) {
        if (byteRead.canRead(1)) {
            throw CborDecodeException("All bytes were not read from blob, at least ${byteRead.suggestAvailableBytes()} remaining")
        }
        if (byteRead === chunkedBlobRead) {
            if (expectedLength != ANY && expectedLength != byteRead.totalBytes) {
                throw CborDecodeException("Expected blob length $expectedLength, got ${byteRead.totalBytes}")
            }
        } else if (byteRead === solidBlobRead) {
            byteRead.resetForWriting(false)// Just lose the reference to read buffer
        }
    }

    //region Quick read functions

    /** @see CborReadSingle.int */
    @Throws(CborDecodeException::class)
    fun int(): Long {
        return read { int() }
    }

    /** @see CborReadSingle.boolean */
    @Throws(CborDecodeException::class)
    fun boolean(): Boolean {
        return read { boolean() }
    }

    /** @see CborReadSingle.null */
    @Throws(CborDecodeException::class)
    fun `null`(): Nothing? {
        return read { `null`() }
    }

    /** @see CborReadSingle.undefined */
    @Throws(CborDecodeException::class)
    fun undefined() {
        return read { undefined() }
    }

    /** @see CborReadSingle.float */
    @Throws(CborDecodeException::class)
    fun float(): Double {
        return read { float() }
    }

    /** @see CborReadSingle.tag */
    @Throws(CborDecodeException::class)
    inline fun <T> tag(expectedTag: Long = ANY_TAG, readTagged: CborRead.(tag: Long) -> T): T {
        return read { tag(expectedTag, readTagged) }
    }

    /** @see CborReadSingle.blob */
    @Throws(CborDecodeException::class)
    fun blob(expectedLength: Int = ANY): ByteArray {
        return read { blob(expectedLength) }
    }

    /** @see CborReadSingle.blob */
    @Throws(CborDecodeException::class)
    inline fun <T> blob(expectedLength: Int = ANY, read: ByteRead.() -> T): T {
        return read { blob(expectedLength, read) }
    }

    /** @see CborReadSingle.string */
    @Throws(CborDecodeException::class)
    fun string(): CharSequence {
        return read { string() }
    }

    /** @see CborReadSingle.arrayRaw */
    @Throws(CborDecodeException::class)
    inline fun <T> array(read: CborRead.(countHint: Int) -> T): T {
        return read { arrayRaw(read) }
    }

    /** @see CborReadSingle.arrayInto */
    @Throws(CborDecodeException::class)
    inline fun <T> arrayInto(out: MutableCollection<T>, read: CborDeserialize<T>) {
        return read { arrayInto(out, read) }
    }

    /** @see CborReadSingle.mapRaw */
    @Throws(CborDecodeException::class)
    inline fun <T> map(read: CborRead.(pairCountHint: Int) -> T): T {
        return read { mapRaw(read) }
    }

    /** @see CborReadSingle.value */
    @Throws(CborDecodeException::class)
    fun <T> value(deserializer: CborDeserialize<T>): T {
        return read {
            with(deserializer) {
                deserialize()
            }
        }
    }

    /** @see CborReadSingle.obj */
    inline fun <T> obj(read: CborReadFields.() -> T):T {
        return read { obj(read) }
    }

    /**
     * Read object sequence as object fields.
     */
    inline fun <T> implicitObj(read: CborReadFields.() -> T): T {
        return read(CborReadFields(this))
    }
    //endregion

    /**
     * Completely reset, after [ByteRead] data changes.
     */
    fun reset() {
        this._headerType = CborValueType.END
        this._headerArgument = 0
        this._payloadRemaining = REMAINING_SEQUENCE
        this._fieldProgress = FIELD_NONE
    }

    companion object {
        /** The byte length/value count can be arbitrary (validation will not be performed). */
        const val ANY = -1

        /** The tag value can be arbitrary */
        const val ANY_TAG = -1L

        @PublishedApi internal const val REMAINING_SEQUENCE = -1
        @PublishedApi internal const val REMAINING_BLOB_CHUNKS = -2
        @PublishedApi internal const val REMAINING_STRING_CHUNKS = -3
        @PublishedApi internal const val REMAINING_INDEFINITE_LIST = -4
        @PublishedApi internal const val REMAINING_INDEFINITE_MAP_NEXT_KEY = -5
        @PublishedApi internal const val REMAINING_INDEFINITE_MAP_NEXT_VALUE = -6
        @PublishedApi internal const val REMAINING_BREAK = -7
        /** There was an error while reading, the whole stream is corrupted */
        @PublishedApi internal const val REMAINING_ERROR = -8

        /** No fields were peeked yet. No argument. */
        @PublishedApi internal const val FIELD_NONE = 0L shl 32
        /** Field in argument was already consumed. */
        @PublishedApi internal const val FIELD_CONSUMED = 1L shl 32
        /** Field in argument was read, but its value was not consumed. */
        @PublishedApi internal const val FIELD_PEEKED = 2L shl 32
        /** There are no more fields in the object/map. */
        @PublishedApi internal const val FIELD_END = 3L shl 32
        @PublishedApi internal const val FIELD_MASK = 0xFFFF_FFFFL shl 32
    }
}

/**
 * Allows to write CBOR values to [ByteWrite].
 * Instances are not thread safe.
 */
@WriteDsl
class CborWrite(out: ByteWrite) {
    @Suppress("CanBePrimaryConstructorProperty")
    @PublishedApi
    internal val out: ByteWrite = out

    @PublishedApi
    internal var valuesWritten: Int = 0

    @PublishedApi
    internal var nextFieldAtLeast = Int.MIN_VALUE

    @PublishedApi
    internal val outBlobChunks: ByteWrite = object : ByteWrite {
        override val totalWrittenBytes: Int
            get() = out.totalWrittenBytes

        override fun writeRawLE(value: Long, bytes: Int) {
            this@CborWrite.cborEncodeTypeAndArgument(CborConstants.MAJOR_2_BLOB, bytes.toLong())
            out.writeRawLE(value, bytes)
        }

        override fun writeRawBE(value: Long, bytes: Int) {
            this@CborWrite.cborEncodeTypeAndArgument(CborConstants.MAJOR_2_BLOB, bytes.toLong())
            out.writeRawBE(value, bytes)
        }

        override fun writeRaw(bytes: ByteArray, start: Int, end: Int) {
            val len = end - start
            this@CborWrite.cborEncodeTypeAndArgument(CborConstants.MAJOR_2_BLOB, len.toLong())
            out.writeRaw(bytes, start, end)
        }
    }

    fun reset() {
        valuesWritten = 0
        nextFieldAtLeast = Int.MIN_VALUE
    }

    @PublishedApi
    internal fun cborEncodeTypeAndArgument(type: Int, argument: Long) {
        val out = out
        val t = type.toLong()
        when (argument) {
            in 0..23 -> out.writeRawBE(t or argument, 1)
            in 24..0xFF -> out.writeRawBE(((t or 24) shl 8) or argument, 2)
            in 0x100..0xFFFF -> out.writeRawBE(((t or 25) shl 16) or argument, 3)
            in 0x10000..0xFFFF_FFFF -> out.writeRawBE(((t or 26) shl 32) or argument, 5)
            else -> {
                out.writeRawBE(t or 27, 1)
                out.writeRawBE(argument, 8)
            }
        }
    }

    fun int(value: Long) {
        valuesWritten++
        if (value >= 0) {
            // Positive
            cborEncodeTypeAndArgument(CborConstants.MAJOR_0_UINT, value)
        } else {
            // Negative
            cborEncodeTypeAndArgument(CborConstants.MAJOR_1_NINT, -(value + 1))
        }
    }

    fun int(value: Int) {
        int(value.toLong())
    }

    fun boolean(value: Boolean) {
        valuesWritten++
        out.writeByte(if (value) CborConstants.CBOR_DATA_TRUE else CborConstants.CBOR_DATA_FALSE)
    }

    fun `null`() {
        valuesWritten++
        out.writeByte(CborConstants.CBOR_DATA_NULL)
    }

    fun undefined() {
        valuesWritten++
        out.writeByte(CborConstants.CBOR_DATA_UNDEFINED)
    }

    inline fun tag(tagValue: Long, writeTaggedValue: CborWrite.() -> Unit) {
        cborEncodeTypeAndArgument(CborConstants.MAJOR_6_TAG, tagValue)
        writeValuePayload(1, writeTaggedValue)
    }

    fun float(value: Half) {
        valuesWritten++
        out.writeRawBE(((CborConstants.MAJOR_7_OTHER.toLong() or 25) shl 16) or (value.raw.toLong() and 0xFFFFL), 3)
    }

    fun float(value: Float) {
        valuesWritten++
        out.writeRawBE(((CborConstants.MAJOR_7_OTHER.toLong() or 26) shl 32) or (value.toRawBits().toLong() and 0xFFFF_FFFFL), 5)
    }

    fun float(value: Double) {
        valuesWritten++
        out.writeRawBE(CborConstants.MAJOR_7_OTHER.toLong() or 27, 1)
        out.writeRawBE(value.toRawBits(), 8)
    }

    /**
     * Encode [bytes] from [start] (incl.) to [end] (excl.) as byte array of determinate size.
     */
    fun blob(bytes: ByteArray, start: Int = 0, end: Int = bytes.size) {
        valuesWritten++
        val len = max(end - start, 0)
        cborEncodeTypeAndArgument(CborConstants.MAJOR_2_BLOB, len.toLong())
        out.writeRaw(bytes, start, end)
    }

    /**
     * Encode exactly [size] bytes inside [write] callback.
     */
    inline fun blob(size: Int, write: ByteWrite.() -> Unit) {
        valuesWritten++
        cborEncodeTypeAndArgument(CborConstants.MAJOR_2_BLOB, size.toLong())
        val writtenByteAmountBefore = out.totalWrittenBytes
        write(out)
        val actuallyWrittenByteAmount = out.totalWrittenBytes - writtenByteAmountBefore
        if (actuallyWrittenByteAmount != size) {
            throw CborEncodeError(size, actuallyWrittenByteAmount, "bytes")
        }
    }

    /**
     * Encode indeterminate amount of bytes inside [write] callback.
     * This is less efficient than the known-size byte array write.
     */
    inline fun blob(write: ByteWrite.() -> Unit) {
        valuesWritten++
        out.writeByte(CborConstants.CBOR_DATA_HEADER_BLOB_INDEFINITE)
        write(outBlobChunks)
        out.writeByte(CborConstants.CBOR_DATA_BREAK)
    }

    fun string(string: String) {
        valuesWritten++
        // Count UTF-8 bytes
        var utf8Len = 0
        string.forEachCodePoint {
            utf8Len += it.utf8Bytes()
        }
        cborEncodeTypeAndArgument(CborConstants.MAJOR_3_STRING, utf8Len.toLong())
        val out = out
        string.forEachCodePoint { cp ->
            when {
                cp <= 0x7F -> {
                    out.writeRawBE(cp.toLong(), 1)
                }
                cp <= 0x7FF -> {
                    val b0 = (0b110_00000 or (cp ushr 6)).toLong()
                    val b1 = (0b10_000000 or (cp and 0b00_111111)).toLong()
                    out.writeRawBE((b0 shl 8) or b1, 2)
                }
                cp <= 0xFFFF -> {
                    val b0 = (0b1110_0000 or (cp ushr 12)).toLong()
                    val b1 = (0b10_000000 or ((cp ushr 6) and 0b00_111111)).toLong()
                    val b2 = (0b10_000000 or (cp and 0b00_111111)).toLong()
                    out.writeRawBE((b0 shl 16) or (b1 shl 8) or b2, 3)
                }
                else -> {
                    val b0 = (0b11110_000 or (cp ushr 18)).toLong()
                    val b1 = (0b10_000000 or ((cp ushr 12) and 0b00_111111)).toLong()
                    val b2 = (0b10_000000 or ((cp ushr 6) and 0b00_111111)).toLong()
                    val b3 = (0b10_000000 or (cp and 0b00_111111)).toLong()
                    out.writeRawBE((b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3, 4)
                }
            }
        }
    }

    /**
     * Encode CBOR array. Each individual item must be encoded in [writeItems] callback.
     * The amount of encoded values is indefinite.
     */
    inline fun array(writeItems: CborWrite.() -> Unit) {
        val outer = valuesWritten + 1
        valuesWritten = 0
        out.writeByte(CborConstants.CBOR_DATA_HEADER_ARRAY_INDEFINITE)
        writeItems()
        out.writeByte(CborConstants.CBOR_DATA_BREAK)
        valuesWritten = outer
    }

    /**
     * Encode CBOR array. Each individual item must be encoded in [writeItems] callback.
     * The amount of encoded values must be exactly [length].
     */
    inline fun array(length: Int, writeItems: CborWrite.() -> Unit) {
        cborEncodeTypeAndArgument(CborConstants.MAJOR_4_ARRAY, length.toLong())
        writeValuePayload(length, writeItems)
    }

    fun <T> array(collection: Collection<T>, serializer: CborSerialize<T>) {
        array(collection.size) {
            for (value in collection) {
                value(value, serializer)
            }
        }
    }

    /**
     * Encode CBOR map. Each individual key and value pair must be encoded in [writeItems] callback.
     * The amount of encoded pairs is indefinite.
     */
    inline fun map(writeItems: CborWrite.() -> Unit) {
        val outer = valuesWritten + 1
        out.writeByte(CborConstants.CBOR_DATA_HEADER_MAP_INDEFINITE)
        valuesWritten = 0
        writeItems()
        if (valuesWritten % 2 != 0) {
            throw CborEncodeError(valuesWritten)
        }
        valuesWritten = outer
        out.writeByte(CborConstants.CBOR_DATA_BREAK)
    }

    /**
     * Encode CBOR map. Each individual key and value pair must be encoded in [writeItems] callback.
     * The amount of encoded pairs must be exactly [length].
     */
    inline fun map(length: Int, writeItems: CborWrite.() -> Unit) {
        cborEncodeTypeAndArgument(CborConstants.MAJOR_5_MAP, length.toLong())
        writeValuePayload(length * 2, writeItems)
    }

    inline fun <K,V> map(m: Map<K, V>, writeKey: CborSerialize<K>, writeValue: CborSerialize<V>) {
        val size = m.size
        map(size) {
            if (size > 0) {
                for (entry in m) {
                    with(writeKey) { serialize(entry.key) }
                    with(writeValue) { serialize(entry.value) }
                }
            }
        }
    }

    inline fun obj(write: CborFieldsWrite.()->Unit) {
        val oldNextField = nextFieldAtLeast
        nextFieldAtLeast = Int.MIN_VALUE
        map {
            write(CborFieldsWrite(this))
        }
        nextFieldAtLeast = oldNextField
    }

    /**
     * Can only be used at top level to write Cbor sequence like object fields
     */
    inline fun implicitObj(write: CborFieldsWrite.()->Unit) {
        val oldNextField = nextFieldAtLeast
        val outer = valuesWritten + 1
        nextFieldAtLeast = Int.MIN_VALUE
        valuesWritten = 0
        write(CborFieldsWrite(this))
        if (valuesWritten % 2 != 0) {
            throw CborEncodeError(valuesWritten)
        }
        valuesWritten = outer
        nextFieldAtLeast = oldNextField
    }

    fun <T> value(value: T, serializer: CborSerialize<T>) {
        val valuesBefore = valuesWritten
        serializer.apply {
            serialize(value)
        }
        if (valuesBefore + 1 != valuesWritten) {
            throw CborEncodeError("CBOR serializers must write exactly 1 value, but $serializer has written ${valuesWritten - valuesBefore}")
        }
    }

    /**
     * Encode [value]. Note that the exact bit-encoding may not be exactly 1:1 to the original CBOR
     * from which [value] might have been parsed, because indeterminate encodings are not persisted
     * (all encodings are determinate).
     */
    fun value(value: CborValue) {
        when (value) {
            is CborValue.Array -> array(value.value.size) {
                for (v in value.value) {
                    value(v)
                }
            }
            is CborValue.Blob -> blob(value.value)
            is CborValue.Float -> when (value.byteWidth) {
                0 -> {
                    val f64 = value.value
                    val f32 = doubleToFloat(value.value)
                    if (f32.toDouble() != f64) {
                        float(f64)
                        return
                    }
                    val f16 = Half(f32)
                    if (f16.toFloat() != f32) {
                        float(f32)
                        return
                    }
                    float(f16)
                }
                2 -> float(Half(doubleToFloat(value.value)))
                4 -> float(doubleToFloat(value.value))
                else -> float(value.value)
            }
            is CborValue.Int -> int(value.value)
            is CborValue.Map -> map(value.value.size) {
                for ((k, v) in value.value) {
                    value(k)
                    value(v)
                }
            }
            is CborValue.Tag -> tag(value.tag) {
                value(value.value)
            }
            is CborValue.Text -> string(value.value)
            CborValue.False -> boolean(false)
            CborValue.True -> boolean(true)
            CborValue.Null -> `null`()
            CborValue.Undefined -> undefined()
        }
    }

    @PublishedApi
    internal fun writeValuePayloadEnd(outer: Int, expectedAmountOfValues:Int) {
        if (valuesWritten != expectedAmountOfValues) {
            throw CborEncodeError(expectedAmountOfValues, valuesWritten, "CBOR values")
        }
        valuesWritten = outer + 1
    }

    /** Used for writing value payload.
     * Calls [writeValues] and asserts that it wrote [expectedAmountOfValues].
     * Increments [valuesWritten] by one for convenience. */
    @PublishedApi
    internal inline fun writeValuePayload(expectedAmountOfValues:Int, writeValues: CborWrite.() -> Unit) {
        val outer = valuesWritten
        valuesWritten = 0
        writeValues()
        writeValuePayloadEnd(outer, expectedAmountOfValues)
    }

    @WriteDsl
    @JvmInline
    value class CborFieldsWrite(@PublishedApi internal val cborWrite: CborWrite) {

        @PublishedApi
        internal fun field(fieldId: Int) {
            val next = cborWrite.nextFieldAtLeast
            if (fieldId < next) {
                throw CborEncodeError("When encoding CBOR object fields, their field IDs must be increasing, but field $fieldId is appearing after $next")
            }
            cborWrite.int(fieldId.toLong())
            cborWrite.nextFieldAtLeast = fieldId + 1
        }

        inline fun field(fieldId: Int, write: CborWrite.() -> Unit) {
            field(fieldId)
            cborWrite.writeValuePayload(1, write)
        }

        fun <T> field(fieldId: Int, value: T, serializer: CborSerialize<T>) {
            field(fieldId)
            cborWrite.value(value, serializer)
        }

        //region Utility functions
        fun field(fieldId: Int, value: Int) {
            field(fieldId)
            cborWrite.int(value)
        }
        fun field(fieldId: Int, value: Long) {
            field(fieldId)
            cborWrite.int(value)
        }
        fun field(fieldId: Int, value: Float) {
            field(fieldId)
            cborWrite.float(value)
        }
        fun field(fieldId: Int, value: Double) {
            field(fieldId)
            cborWrite.float(value)
        }

        fun fieldOrZero(fieldId: Int, value: Int) {
            if (value != 0) {
                field(fieldId)
                cborWrite.int(value)
            }
        }
        fun fieldOrZero(fieldId: Int, value: Long) {
            if (value != 0L) {
                field(fieldId)
                cborWrite.int(value)
            }
        }
        fun fieldOrZero(fieldId: Int, value: Float) {
            if (value != 0f) {
                field(fieldId)
                cborWrite.float(value)
            }
        }
        fun fieldOrZero(fieldId: Int, value: Double) {
            if (value != 0.0) {
                field(fieldId)
                cborWrite.float(value)
            }
        }
        fun field(fieldId: Int, value: String) {
            field(fieldId)
            cborWrite.string(value)
        }
        fun fieldOrNull(fieldId: Int, value: String?) {
            if (value != null) {
                field(fieldId)
                cborWrite.string(value)
            }
        }
        fun fieldOrNull(fieldId: Int, value: ByteArray?) {
            if (value != null) {
                field(fieldId)
                cborWrite.blob(value)
            }
        }
        fun field(fieldId: Int, value: ByteArray) {
            field(fieldId)
            cborWrite.blob(value)
        }

        fun <T> fieldOrNull(fieldId: Int, value: T?, serializer: CborSerialize<T>) {
            if (value != null) {
                field(fieldId)
                cborWrite.value(value, serializer)
            }
        }

        fun <T> fieldOr(fieldId: Int, value: T, defaultValue: T, serializer: CborSerialize<T>) {
            if (value != defaultValue) {
                field(fieldId)
                cborWrite.value(value, serializer)
            }
        }
        //endregion
    }
}

sealed class CborValue {
    class Int(val value: Long) : CborValue() {
        override fun appendTo(sb: StringBuilder) {
            sb.append(value)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Int

            if (value != other.value) return false

            return true
        }

        override fun hashCode(): kotlin.Int {
            return value.hashCode()
        }
    }

    class Float(val value: Double, val byteWidth: kotlin.Int = 0) : CborValue() {
        override fun appendTo(sb: StringBuilder) {
            sb.append(value)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Float

            // Using compare instead of equals so that NaN == NaN
            return value.compareTo(other.value) == 0
        }

        override fun hashCode(): kotlin.Int {
            return value.hashCode()
        }
    }

    class Text(val value: String): CborValue() {
        override fun appendTo(sb: StringBuilder) {
            sb.append('"')
            for (c in value) {
                if (c.code in 0x00..0x1F) {
                    sb.append("\\u")
                    val hex = c.code.toString(16).uppercase()
                    for (padding in 0 until 4 - hex.length) {
                        sb.append('0')
                    }
                    sb.append(hex)
                } else if (c == '\\' || c == '"') {
                    sb.append('\\')
                    sb.append(c)
                } else {
                    sb.append(c)
                }
            }
            sb.append('"')
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Text

            if (value != other.value) return false

            return true
        }

        override fun hashCode(): kotlin.Int {
            return value.hashCode()
        }
    }

    class Blob(val value: ByteArray): CborValue() {
        override fun appendTo(sb: StringBuilder) {
            sb.append("h'")
            sb.appendHexBytes(value)
            sb.append('\'')
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Blob

            if (!value.contentEquals(other.value)) return false

            return true
        }

        override fun hashCode(): kotlin.Int {
            return value.contentHashCode()
        }
    }

    class Array(val value: List<CborValue>): CborValue() {
        override fun appendTo(sb: StringBuilder) {
            sb.append('[')
            for ((i, value) in value.withIndex()) {
                if (i > 0) {
                    sb.append(", ")
                }
                value.appendTo(sb)
            }
            sb.append(']')
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Array

            if (value != other.value) return false

            return true
        }

        override fun hashCode(): kotlin.Int {
            return value.hashCode()
        }

        override fun isValid(): Boolean = value.all { it.isValid() }
    }

    class Map(val value: List<Pair<CborValue, CborValue>>): CborValue() {

        override fun appendTo(sb: StringBuilder) {
            sb.append('{')
            for ((i, pair) in value.withIndex()) {
                if (i > 0) {
                    sb.append(", ")
                }
                pair.first.appendTo(sb)
                sb.append(": ")
                pair.second.appendTo(sb)
            }
            sb.append('}')
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Map

            if (value != other.value) return false

            return true
        }

        override fun hashCode(): kotlin.Int {
            return value.hashCode()
        }

        override fun isValid(): Boolean = value.all { it.first.isValid() && it.second.isValid() }
    }

    class Tag(val tag: Long, val value: CborValue): CborValue() {
        override fun appendTo(sb: StringBuilder) {
            sb.append(tag).append('(')
            value.appendTo(sb)
            sb.append(')')
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Tag

            if (tag != other.tag) return false
            if (value != other.value) return false

            return true
        }

        override fun hashCode(): kotlin.Int {
            var result = tag.hashCode()
            result = 31 * result + value.hashCode()
            return result
        }

        override fun isValid(): Boolean = value.isValid()
    }

    object False : CborValue() {
        override fun appendTo(sb: StringBuilder) {
            sb.append("false")
        }
    }

    object True : CborValue() {
        override fun appendTo(sb: StringBuilder) {
            sb.append("true")
        }
    }

    object Null : CborValue() {
        override fun appendTo(sb: StringBuilder) {
            sb.append("null")
        }
    }

    object Undefined : CborValue() {
        override fun appendTo(sb: StringBuilder) {
            sb.append("undefined")
        }
    }

    protected abstract fun appendTo(sb: StringBuilder)

    override fun toString(): String {
        return buildString {
            appendTo(this)
        }
    }

    open fun isValid(): Boolean = true
}

@PublishedApi
internal object CborConstants {
    // https://www.rfc-editor.org/rfc/rfc8949.html#name-major-types
    const val MAJOR_MASK = 0b111_00000
    const val MAJOR_0_UINT = 0b000_00000
    const val MAJOR_1_NINT = 0b001_00000
    const val MAJOR_2_BLOB = 0b010_00000
    const val MAJOR_3_STRING = 0b011_00000
    const val MAJOR_4_ARRAY = 0b100_00000
    const val MAJOR_5_MAP = 0b101_00000
    const val MAJOR_6_TAG = 0b110_00000
    const val MAJOR_7_OTHER = 0b111_00000

    const val MINOR_MASK = 0b000_11111

    const val MINOR_INDEFINITE = 0b000_11111

    const val CBOR_DATA_HEADER_BLOB_INDEFINITE: Byte = (MAJOR_2_BLOB or MINOR_INDEFINITE).toByte()
    const val CBOR_DATA_HEADER_ARRAY_INDEFINITE: Byte = (MAJOR_4_ARRAY or MINOR_INDEFINITE).toByte()
    const val CBOR_DATA_HEADER_MAP_INDEFINITE: Byte = (MAJOR_5_MAP or MINOR_INDEFINITE).toByte()

    const val CBOR_DATA_FALSE: Byte = (MAJOR_7_OTHER or 20).toByte()
    const val CBOR_DATA_TRUE: Byte = (MAJOR_7_OTHER or 21).toByte()
    const val CBOR_DATA_NULL: Byte = (MAJOR_7_OTHER or 22).toByte()
    const val CBOR_DATA_UNDEFINED: Byte = (MAJOR_7_OTHER or 23).toByte()
    const val CBOR_DATA_BREAK: Byte = (MAJOR_7_OTHER or 31).toByte()
}

/** A function that can serialize [T] into CBOR. */
fun interface CborSerialize<T> {
    /**
     * Encode [value] as a single CBOR value into receiver.
     * @throws CborEncodeError when the encoding code is faulty.
     */
    @Throws(CborEncodeError::class)
    fun CborWrite.serialize(value: T)
}

/** A function that can deserialize [T] from CBOR. */
fun interface CborDeserialize<T> {
    /**
     * Decode value of type [T] that is next in receiver [CborRead].
     * @throws CborDecodeException when the [CborRead] does not hold a correct decoding of the object.
     */
    @Throws(CborDecodeException::class)
    fun CborReadSingle.deserialize(): T
}

/**
 * Serializer to and from CBOR.
 * Ideally implemented as an object or at least globally instantiated class.
 * Must be thread safe.
 */
interface CborSerializer<T> : CborSerialize<T>, CborDeserialize<T>

/** Convenience function to serialize object into CBOR bytes. */
fun <T> CborSerialize<T>.serializeToBytes(value: T): ByteArray {
    val data = ByteData()
    val cw = CborWrite(data)
    cw.value(value, this)
    return data.toByteArray()
}

inline fun serializeCborToBytes(block: CborWrite.() -> Unit): ByteArray {
    val data = ByteData()
    val cw = CborWrite(data)
    cw.block()
    return data.toByteArray()
}

/** Convenience function to deserialize CBOR bytes into an object. */
fun <T> CborDeserialize<T>.deserializeBytes(value: ByteArray, start: Int = 0, end: Int = value.size): T {
    val data = ByteData()
    data.resetForReading(value, start, end)
    val cw = CborRead(data)
    return cw.value(this)
}

inline fun <T> deserializeCborFromBytes(value: ByteArray, start: Int = 0, end: Int = value.size, block: CborRead.() -> T):T {
    val data = ByteData()
    data.resetForReading(value, start, end)
    val cw = CborRead(data)
    return cw.block()
}

typealias CborReadSingle = CborRead.CborReadSingle

class CborDecodeException(message: String) : Exception(message) {
    constructor(expected: String, got: CborValue?) : this("Expected $expected, got ${got ?: "<break>"}")
    constructor(whenReading: String, expected: Int, what: String) : this(whenReading, expected.toLong(), what)
    constructor(whenReading: String, expected: Long, what: String) : this("When reading $whenReading, expected $expected $what")
}

/** Thrown when decoding assertions are broken. */
class CborDecodeError(message: String) : AssertionError(message)

class CborEncodeError(message: String) : Exception(message) {
    constructor(expectedAmount: Int, actualAmount: Int, unit: String) : this("Expected $expectedAmount $unit written, $actualAmount actually written")
    constructor(unevenAmountOfMapItems: Int): this("The amount of encoded map items must be even, but is $unevenAmountOfMapItems")
}

// float16 (Half) based on
// https://github.com/x448/float16/blob/3aa25b6cb1e5dac285fd269fd0117f6cdb635b63/float16.go
// under MIT: Copyright (c) 2019-present Montgomery Edwards and Faye Amacker
@JvmInline
value class Half(val raw: Int) {

    constructor(f32: Float): this(f32.run {
        val u32 = f32.toRawBits()

        val sign = u32 and 0x80000000.toInt()
        val exp = u32 and 0x7f800000
        val coef = u32 and 0x007fffff

        if (exp == 0x7f800000) {
            // NaN or Infinity
            var nanBit = 0
            if (coef != 0) {
                nanBit = 0x0200
            }
            return@run (sign ushr 16) or 0x7c00 or nanBit or (coef ushr 13)
        }

        val halfSign = sign ushr 16

        val unbiasedExp = (exp ushr 23) - 127
        val halfExp = unbiasedExp + 15

        if (halfExp >= 0x1f) {
            return@run halfSign or 0x7c00
        }

        if (halfExp <= 0) {
            if (14 - halfExp > 24) {
                return@run halfSign
            }
            val c = coef or 0x00800000
            var halfCoef = c ushr (14 - halfExp)
            val roundBit = 1 shl (13 - halfExp)
            if ((c and roundBit) != 0 && (c and (3 * roundBit - 1)) != 0) {
                halfCoef++
            }
            return@run halfSign or halfCoef
        }

        val uHalfExp = halfExp shl 10
        val halfCoef = coef ushr 13
        val roundBit = 0x00001000
        if ((coef and roundBit) != 0 && (coef and (3 * roundBit - 1)) != 0) {
            return@run (halfSign or uHalfExp or halfCoef) + 1
        }
        return@run halfSign or uHalfExp or halfCoef
    })

    fun toFloat(): Float {
        val f16 = raw
        val sign: Int = (f16 and 0x8000) shl 16 // sign for 32-bit
        var exp: Int = (f16 and 0x7c00) ushr 10  // exponenent for 16-bit
        var coef: Int = (f16 and 0x03ff) shl 13 // significand for 32-bit

        val f32Bits = if (exp == 0x1f) {
            if (coef == 0) {
                // infinity
                sign or 0x7f800000
            } else {
                // NaN
                sign or 0x7fc00000 or coef
            }
        } else if (exp == 0 && coef == 0) {
            // zero
            sign
        } else {
            if (exp == 0) {
                // normalize subnormal numbers
                exp++
                while (coef and 0x7f800000 == 0) {
                    coef = coef shl 1
                    exp--
                }
                coef = coef and 0x007fffff
            }

            sign or ((exp + (0x7f - 0xf)) shl 23) or coef
        }
        return Float.fromBits(f32Bits)
    }
}