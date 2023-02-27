package com.darkyen.cbor

import java.time.Instant
import java.util.*

object CborSerializersJVM {
    object UUIDSerializer : CborSerializer<UUID> {
        // https://www.iana.org/assignments/cbor-tags/cbor-tags.xhtml
        const val TAG = 37L

        override fun CborWrite.serialize(value: UUID) {
            tag(TAG) {
                blob(16) {
                    writeRawBE(value.mostSignificantBits, 8)
                    writeRawBE(value.leastSignificantBits, 8)
                }
            }
        }

        override fun CborReadSingle.deserialize(): UUID {
            return tag(TAG) {
                blob(16) {
                    val msb = readRawBE(8)
                    val lsb = readRawBE(8)
                    UUID(msb, lsb)
                }
            }
        }
    }

    object InstantSerializer : CborSerializer<Instant> {
        const val TAG = 99L// millis since epoch is not defined, floats in seconds since epoch would not be efficient, so this is an unassigned tag next to days since epoch, which is similar
        override fun CborWrite.serialize(value: Instant) {
            tag(TAG) {
                int(value.toEpochMilli())
            }
        }

        override fun CborReadSingle.deserialize(): Instant {
            return tag(TAG) {
                Instant.ofEpochMilli(int())
            }
        }
    }
}