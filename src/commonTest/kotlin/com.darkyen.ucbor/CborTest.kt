package com.darkyen.ucbor

import kotlin.math.PI
import kotlin.random.Random
import kotlin.test.*

class CborTest {

    fun generateTestValues(seed: Int = 666): List<CborValue> {
        val random = Random(seed)

        val simpleValues = arrayOf(
            CborValue.Int(1L),
            CborValue.Int(-1L),
            CborValue.Int(100L),
            CborValue.Int(-100L),
            CborValue.Int(1000L),
            CborValue.Int(-1000L),
            CborValue.Int(1000000000000L),
            CborValue.Int(-1000000000000L),
            CborValue.Int(Long.MAX_VALUE),
            CborValue.Int(Long.MIN_VALUE),

            CborValue.Undefined,
            CborValue.Null,
            CborValue.False,
            CborValue.True,

            CborValue.Float(0.0, 2),
            CborValue.Float(0.0, 4),
            CborValue.Float(0.0, 8),
            CborValue.Float(Double.POSITIVE_INFINITY, 4),
            CborValue.Float(Double.NEGATIVE_INFINITY, 4),
            CborValue.Float(55.0),// F952E0
            CborValue.Float(55.55), // FB404BC66666666666
            CborValue.Float(-55.55),// FBC04BC66666666666
            CborValue.Float(5500.5),// FA45ABE400 To force usage of automatic float32
            CborValue.Float(PI),// FB400921FB54442D18
            CborValue.Float(doubleToFloat(PI).toDouble(), 4),// FA40490FDB
            CborValue.Float(-PI),// FBC00921FB54442D18
            CborValue.Float(doubleToFloat(-PI).toDouble(), 4),// FAC0490FDB
            CborValue.Float(Double.NaN),// FB7FF8000000000000
            CborValue.Float(Double.MAX_VALUE),// FB7FEFFFFFFFFFFFFF
            CborValue.Float(-Double.MAX_VALUE),// FBFFEFFFFFFFFFFFFF
            CborValue.Float(Double.MIN_VALUE), // FB0000000000000001
            CborValue.Float(-Double.MIN_VALUE), // FB8000000000000001
            CborValue.Float(Float.MAX_VALUE.toDouble()),// FA7F7FFFFF
            CborValue.Float(Float.MIN_VALUE.toDouble()),// FA00000001

            CborValue.Text(""),
            CborValue.Text("Thank you, Rhonda, that is a good point."),
            CborValue.Text(random.nextBytes(100).contentToString()),

            CborValue.Blob(ByteArray(0)),
            CborValue.Blob(random.nextBytes(1)),
            CborValue.Blob(random.nextBytes(100)),
            CborValue.Blob(random.nextBytes(10_000)),

            CborValue.Array(emptyList()),
            CborValue.Map(emptyList()),
        )

        val values = simpleValues.toMutableList()
        // Add tagged, array and map variants of everything
        for (i in 0 until values.size) {
            val v = values[i]
            values.add(CborValue.Tag(random.nextLong(Long.MAX_VALUE), v))
            values.add(CborValue.Array(listOf(v)))
            val v2 = values[i + 1]
            values.add(CborValue.Map(listOf(v to v2)))
            values.add(CborValue.Map(listOf(v2 to v)))
        }

        // Add some random extra values
        for (r in 0 until 100) {
            when (random.nextInt(3)) {
                0 -> {
                    // Tagged
                    values.add(CborValue.Tag(random.nextLong(Long.MAX_VALUE), values.random(random)))
                }
                1 -> {
                    // Array
                    val len = random.nextInt(2, 20)
                    val arr = ArrayList<CborValue>(len)
                    for (i in 0 until len) {
                        arr.add(values.random(random))
                    }
                    values.add(CborValue.Array(arr))
                }
                2 -> {
                    // Map
                    val len = random.nextInt(2, 20)
                    val arr = ArrayList<Pair<CborValue, CborValue>>(len)
                    for (i in 0 until len) {
                        arr.add(values.random(random) to values.random(random))
                    }
                    values.add(CborValue.Map(arr))
                }
            }
        }

        return values
    }

    @Test
    fun basics() {
        val testValues = generateTestValues()

        // Test reproducibility of test value generation and equals()
        for ((value1, value2) in testValues.zip(generateTestValues())) {
            assertEquals(value1, value2)
            assertEquals(value1.hashCode(), value2.hashCode())
        }

        // Passthrough test
        val data = ByteData()
        for ((i, value) in testValues.withIndex()) {
            try {
                passthroughTest(data, value)
                skipTest(data, value)
            } catch (t: Throwable) {
                println("Error at i= $i on $value")
                println(data.toByteArray().toHexString())
                throw t
            }
        }
    }

    @Ignore
    @Test
    fun floatTest() {
        // This tests differences between floats in browser and on JVM

        val data = ByteData()

        for (v in arrayOf(
            CborValue.Float(55.55),
            CborValue.Float(5500.5)
        )) {
            println("$v")

            data.resetForWriting(true)
            data.writeRawBE(v.value.toRawBits(), 8)
            println("Double: ${data.toByteArray().toHexString()}")


            data.resetForWriting(true)
            data.writeRawBE(doubleToFloat(v.value).toDouble().toRawBits(), 8)
            println("doubleToFloat(Double).toDouble(): ${data.toByteArray().toHexString()}")

            data.resetForWriting(true)
            val vD1 = v.value
            val vF1 = vD1.toFloat()
            val vD2 = vF1.toDouble()
            data.writeRawBE(vD2.toRawBits(), 8)
            println("Double->Float->Double: ${data.toByteArray().toHexString()}")

            data.resetForWriting(true)
            data.writeRawBE(v.value.toFloat().toRawBits().toLong(), 4)
            println("Float: ${data.toByteArray().toHexString()}")

            data.resetForWriting(true)
            val write = CborWrite(data)
            write.value(v)
            val read = CborRead(data)
            val readValue = read.value()
            assertEquals(v, readValue, "CBOR: ${data.toByteArray().toHexString()}")
        }
    }

    @Test
    fun objTest() {
        wr({
            obj {
                field(1) { int(55) }
            }
        }, {
            obj {
                field(1) { assertEquals(55, int()) }
            }
        })

        wr({
            obj {
                field(1) { int(55) }
            }
        }, {
            obj {
                field(0, { fail("this field should not exist") }, {})
            }
        })

        wr({
            obj {
                field(1) { int(55) }
                field(55, 55, CborSerializers.IntSerializer)
            }
        }, {
            obj {
                field(2, { fail("this field should not exist") }, {})
            }
        })

        val randomBytes = "All of your gang is from Leyawiin".encodeToByteArray()
        wr({
            obj {
                field(999) {
                    blob {
                        writeRawBE(1234567890L, 6)
                        writeRawLE(1234567890L, 6)
                        writeRaw(randomBytes)
                    }
                }
            }
        }, {
            obj {
                field(999) {
                    blob {
                        assertEquals(1234567890L, readRawBE(6))
                        assertEquals(1234567890L, readRawLE(6))
                        val readBytes = ByteArray(randomBytes.size)
                        val r = readRaw(readBytes)
                        assertEquals(r, readBytes.size)
                        assertContentEquals(randomBytes, readBytes)
                    }
                }
            }
        })

        wr({
            obj {
                field(999) {
                    blob {
                        writeRawBE(1234567890L, 6)
                        writeRawLE(1234567890L, 6)
                        writeRaw(randomBytes)
                    }
                }
            }
        }, {
            obj {
                field(999) {
                    blob {
                        readSkip(12)
                        val readBytes = ByteArray(randomBytes.size)
                        val r = readRaw(readBytes)
                        assertEquals(r, readBytes.size)
                        assertContentEquals(randomBytes, readBytes)
                    }
                }
            }
        })

        wr({
            obj {
                field(999) {
                    blob(randomBytes.size + 12) {
                        writeRawBE(1234567890L, 6)
                        writeRawLE(1234567890L, 6)
                        writeRaw(randomBytes)
                    }
                }
            }
        }, {
            obj {
                field(999) {
                    blob {
                        assertEquals(1234567890L, readRawBE(6))
                        assertEquals(1234567890L, readRawLE(6))
                        val readBytes = ByteArray(randomBytes.size)
                        val r = readRaw(readBytes)
                        assertEquals(r, readBytes.size)
                        assertContentEquals(randomBytes, readBytes)
                    }
                }
            }
        })

        wr({
            obj {
                field(999) {
                    blob(randomBytes.size + 12) {
                        writeRawBE(1234567890L, 6)
                        writeRawLE(1234567890L, 6)
                        writeRaw(randomBytes)
                    }
                }
            }
        }, {
            obj {
                field(999) {
                    blob {
                        assertEquals(randomBytes.size + 12, readSkip(9999999))
                    }
                }
            }
        })

        wr({
            obj {
                field(999) {
                    blob() {
                        writeRawBE(1234567890L, 6)
                        writeRawLE(1234567890L, 6)
                        writeRaw(randomBytes)
                    }
                }
            }
        }, {
            obj {
                field(999) {
                    blob {
                        assertEquals(randomBytes.size + 12, readSkip(9999999))
                    }
                }
            }
        })

        wr({
            array(3) {
                obj {
                    field(0, 10)
                    field(1, 11)
                }
                obj {
                    field(0, 20)
                    field(1, 21)
                }
                obj {
                    field(0, 30)
                    field(1, 31)
                }
            }
        }, {
            array { items ->
                assertEquals(3, items)
                var iteration = 0
                do {
                    val done = read {
                        if (it == CborRead.CborValueType.END) {
                            true
                        } else {
                            iteration++
                            obj {
                                assertEquals(10*iteration, fieldInt32OrZero(0))
                                assertEquals(10*iteration + 1, fieldInt32OrZero(1))
                                assertEquals(0, fieldInt32OrZero(2))
                            }
                            false
                        }
                    }
                } while (!done)
                assertEquals(3, iteration)
            }
        })

        wr({
            obj {
                field(0) {
                    array(3) {
                        obj {
                            field(0, 10)
                            field(1, 11)
                            field(2, 12)
                        }
                        obj {
                            field(0, 20)
                            field(1, 21)
                            field(2, 22)
                        }
                        obj {
                            field(0, 30)
                            field(1, 31)
                            field(2, 32)
                        }
                    }
                }
                field(1, 55)
            }
        }, {
            obj {
                field(0) {
                    arrayRaw { items ->
                        assertEquals(3, items)
                        var iteration = 0
                        do {
                            val done = read {
                                if (it == CborRead.CborValueType.END) {
                                    true
                                } else {
                                    iteration++
                                    obj {
                                        assertEquals(10*iteration, fieldInt32OrZero(0))
                                        assertEquals(10*iteration + 1, fieldInt32OrZero(1))
                                        assertEquals(10*iteration + 2, fieldInt32OrZero(2))
                                        assertEquals(0, fieldInt32OrZero(3))
                                    }
                                    false
                                }
                            }
                        } while (!done)
                        assertEquals(3, iteration)
                    }
                }
                assertEquals(55, fieldIntOrZero(1))
            }
        })

        assertFailsWith<CborDecodeException> {
            wr({
                obj {
                    field(999) {
                        blob() {
                            writeRawBE(1234567890L, 6)
                            writeRawLE(1234567890L, 6)
                            writeRaw(randomBytes)
                        }
                    }
                }
            }, {
                obj {
                    field(999) {
                        blob {
                            assertEquals(7, readSkip(7))
                        }
                    }
                }
            })
        }

        assertFailsWith<CborDecodeException> {
            wr({
                obj {
                    field(999) {
                        blob() {
                            writeRawBE(1234567890L, 6)
                            writeRawLE(1234567890L, 6)
                            writeRaw(randomBytes)
                        }
                    }
                }
            }, {
                obj {
                    field(999) {
                        blob(randomBytes.size + 12 + 666) {
                            assertEquals(7, readSkip(7))
                            assertEquals(randomBytes.size + 12 - 7, readSkip(9999))
                        }
                    }
                }
            })
        }

        assertFailsWith<CborDecodeException> {
            wr({
                obj {
                    field(999) {
                        blob(randomBytes.size + 12) {
                            writeRawBE(1234567890L, 6)
                            writeRawLE(1234567890L, 6)
                            writeRaw(randomBytes)
                        }
                    }
                }
            }, {
                obj {
                    field(999) {
                        blob(randomBytes.size + 12 + 666) {
                            assertEquals(7, readSkip(7))
                            assertEquals(randomBytes.size + 12 - 7, readSkip(9999))
                        }
                    }
                }
            })
        }

        wr({
            obj {
                field(999) {
                    blob(randomBytes.size) {
                        writeRaw(randomBytes)
                    }
                }
            }
        }, {
            obj {
                field(999) {
                    assertContentEquals(randomBytes, blob(randomBytes.size))
                }
            }
        })

        wr({
            obj {
                field(999) {
                    blob {
                        writeRaw(randomBytes)
                    }
                }
            }
        }, {
            obj {
                field(999) {
                    assertContentEquals(randomBytes, blob())
                }
            }
        })

        assertFailsWith<CborEncodeError> {
            wr({
                    blob(15) {
                        writeInt(15)
                    }
            }, {})
        }

        assertFailsWith<CborEncodeError> {
            wr({
                map {
                    string("Key without value")
                }
            }, {})
        }

        assertFailsWith<CborEncodeError> {
            wr({
                map {
                    value("Key without value", object : CborSerializer<String> {
                        override fun CborWrite.serialize(value: String) {
                            for (c in value) {
                                int(c.code.toLong())
                            }
                        }

                        override fun CborReadSingle.deserialize(): String {
                            fail("no deserialization in this test")
                        }
                    })
                }
            }, {})
        }

        wr({
                array {
                    int(10)
                    int(11)
                    int(12)
                }
        }, {
            array {
                assertEquals(0, it)
                assertEquals(10, int())
                assertEquals(11, int())
                assertEquals(12, int())
                assertEquals(false, skipValue())
            }
        })

        assertFailsWith<CborEncodeError> {
            wr({
                obj {
                    field(10) { int(10) }
                    field(5) { int(5) }
                }
            }, {})
        }

        assertFailsWith<CborDecodeError> {
            wr({
                map {
                    int(10); int(10)
                    int(5); int(5)
                }
            }, {
                obj {
                    field(10) {
                        assertEquals(10, int())
                    }
                    field(5) {
                        fail("Should not be readable, fields must be ordered")
                    }
                }
            })
        }

        wr({
            obj {
                field(10) { int(10) }
                field(15) { int(15) }
            }
        }, {
            obj {
                field(10) {
                    assertEquals(10, int())
                }
                field(11, { fail("nope") }, { })
                field(15) {
                    assertEquals(15, int())
                }
                field(16, { fail("nope") }, { })
            }
        })

        wr({
            obj {
                field(10) { int(10) }
                field(15) { int(15) }
            }
        }, {
            obj {
                for (i in 0 until 20) {
                    field(i, {
                        val value = int()
                        assertTrue(i == 10 || i == 15, "$i")
                        assertEquals(i.toLong(), value)
                    }, {
                        assertFalse(i == 10 || i == 15, "$i")
                    })
                }
            }
        })

        wr({
            obj {
                field(10) { int(10) }
                field(15) { int(15) }
            }
        }, {
            obj {
                for (i in 0 until 20 step 2) {
                    field(i, {
                        val value = int()
                        assertTrue(i == 10 || i == 15, "$i")
                        assertEquals(i.toLong(), value)
                    }, {
                        assertFalse(i == 10 || i == 15, "$i")
                    })
                }
            }
        })

        wr({
            map {
                int(Long.MIN_VALUE + 100); int(Long.MIN_VALUE + 100)
                int(10); int(10)
                int(15); int(15)
                int(Long.MAX_VALUE - 100); int(Long.MAX_VALUE - 100)
                int(15); int(15)
            }
        }, {
            obj {
                for (i in 0 until 20 step 2) {
                    field(i, {
                        val value = int()
                        assertTrue(i == 10 || i == 15, "$i")
                        assertEquals(i.toLong(), value)
                    }, {
                        assertFalse(i == 10 || i == 15, "$i")
                    })
                }
            }
        })

        wr({
            map {
                int(Long.MIN_VALUE + 100); int(Long.MIN_VALUE + 100)
                int(Long.MIN_VALUE + 100); int(Long.MIN_VALUE + 100)
                int(Long.MIN_VALUE + 100); int(Long.MIN_VALUE + 100)
                int(10); int(10)
                int(15); int(15)
                int(Long.MAX_VALUE - 100); int(Long.MAX_VALUE - 100)
                int(Long.MAX_VALUE - 100); int(Long.MAX_VALUE - 100)
                int(Long.MAX_VALUE - 100); int(Long.MAX_VALUE - 100)
            }
        }, {
            obj {
                for (i in 0 until 20 step 2) {
                    field(i, {
                        val value = int()
                        assertTrue(i == 10 || i == 15, "$i")
                        assertEquals(i.toLong(), value)
                    }, {
                        assertFalse(i == 10 || i == 15, "$i")
                    })
                }
            }
        })

        wr({
            map {
                int(Long.MIN_VALUE + 100); int(Long.MIN_VALUE + 100)
                int(10); int(10)
                int(15); int(15)
                int(55); int(55)
                int(56); int(56)
                int(57); int(58)
            }
        }, {
            obj {
                for (i in 0 until 20 step 2) {
                    field(i, {
                        val value = int()
                        assertTrue(i == 10 || i == 15, "$i")
                        assertEquals(i.toLong(), value)
                    }, {
                        assertFalse(i == 10 || i == 15, "$i")
                    })
                }
            }
        })

        wr({
            map {
                int(Long.MIN_VALUE + 100); int(Long.MIN_VALUE + 100)
                int(10); int(10)
                int(15); int(15)
                int(55); int(55)
                int(56); int(56)
                int(57); int(58)
            }
        }, {
            obj {
            }
        })
    }

    @Test
    fun implicitObjTest() {
        wr({
            implicitObj {}
        },{
            implicitObj {
                read {
                    assertEquals(CborRead.CborValueType.END, type)
                }
            }
        })

        wr({
            implicitObj {
                field(5, 55)
            }
        },{
            implicitObj {
                assertEquals(55, fieldInt32OrZero(5))

                read {
                    assertEquals(CborRead.CborValueType.END, type)
                }
            }
        })

        wr({
            implicitObj {
                field(5, 55)
                field(6, 66)
            }
        },{
            implicitObj {
                assertEquals(55, fieldInt32OrZero(5))
                assertEquals(66, fieldInt32OrZero(6))

                read {
                    assertEquals(CborRead.CborValueType.END, type)
                }
            }
        })
    }

    @Test
    fun arrayCollectionTest() {
        for (list in arrayOf<Collection<String>>(
            emptyList(),
            listOf("Something"),
            listOf("Something", "Else"),
            listOf("Something", "Else", "Entirely"),
        )) {
            wr({
                array(list, CborSerializers.StringSerializer)
            }, {
                val out = ArrayList<String>()
                read {
                    arrayInto(out, CborSerializers.StringSerializer)
                }
                assertContentEquals(list, out)
            })
        }
    }
}