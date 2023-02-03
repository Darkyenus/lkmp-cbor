package com.darkyen.ucbor

import kotlin.test.*
import com.esotericsoftware.jsonbeans.JsonReader
import java.io.File
import java.net.URL

class CborTestVectorsTest {

    @Test
    fun vectors() {
        val data = URL("https://raw.githubusercontent.com/Darkyenus/cbor-test-vectors/master/vectors.json").openStream().use {
            JsonReader().parse(it)
        }

        val myFeatures = setOf(
            "int63",
            "!int64",
            "mapdupes",
            "simple",
            "float16",
            "!bignum",
        )

        val bd = ByteData()
        for (vectorObject in data) {
            val hex = vectorObject.getString("hex").trim()
            try {
                val flags = vectorObject.get("flags")?.asStringArray()?.toSet() ?: emptySet()
                val features = vectorObject.get("features")?.asStringArray()?.toSet() ?: emptySet()
                val diagnostic = vectorObject.get("diagnostic")?.asString()

                if (!myFeatures.containsAll(features)) {
                    // Skipping test
                    println("Skipping $hex because of unsupported features: ${features - myFeatures}")
                    continue
                }

                bd.resetForWriting(true)
                for (i in 0 until hex.length step 2) {
                    bd.writeByte(hex.substring(i, i + 2).toInt(16).toByte())
                }

                var valid = "valid" in flags
                if (!valid && "invalid" !in flags) {
                    throw AssertionError("Test $hex is neither valid or invalid")
                }
                if (valid && "simple" in features) {
                    valid = false
                }

                if (valid) {
                    val cr = CborRead(bd)
                    val value = cr.value()
                    assertFalse(bd.canRead(1))
                    assertNotEquals(null, value)
                    assertTrue(value!!.isValid())

                    assertNull(cr.value())

                    if ("canonical" in flags) {
                        val out = ByteData()
                        val cw = CborWrite(out)
                        cw.value(value)

                        assertContentEquals(bd.toByteArray(), out.toByteArray())
                    }

                    passthroughTest(ByteData(), value)

                    // Skip testing
                    skipTest(ByteData(), value)
                    // Also skip on raw data
                    bd.rewindReading()
                    val skipped = cr.skipValue()
                    assertTrue(skipped)
                    assertNull(cr.value())
                    assertFalse(bd.canRead(1))
                    val skippedEof = cr.skipValue()
                    assertFalse(skippedEof)

                    if (diagnostic != null) {
                        val expected = if ("float" in flags) {
                            floatDiagnosticAlts[diagnostic] ?: diagnostic
                        } else diagnostic
                        assertEquals(expected, value.toString())
                    }
                } else {
                    val cr = CborRead(bd)
                    try {
                        val value = cr.value()
                        // Exception was not thrown, ok, expect something invalid inside

                        if (value != null) {
                            assertTrue(!value.isValid() || bd.canRead(1), "$hex -> $value")
                        } else {
                            assertNotEquals(null, value)
                        }
                    } catch (e: CborDecodeException) {
                        // This is expected
                    }
                }
            } catch (e: Throwable) {
                println("Problem on test $hex")
                throw e
            }
        }
    }

    private val floatDiagnosticAlts: Map<String, String> = mapOf(
        "3.40282346638529e+38" to "3.4028234663852886E38",
        "1.0e+300" to "1.0E300",
        "5.96046447753906e-8" to "5.9604644775390625E-8",
        "6.103515625e-5" to "6.103515625E-5",
        "1(1363896240.5)" to "1(1.3638962405E9)",
    )

}