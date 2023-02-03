package com.darkyen.ucbor

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


fun passthroughTest(data: ByteData, value: CborValue) {
    data.resetForWriting(false)
    val write = CborWrite(data)
    write.value(value)
    //println("$value -> ${data.toByteArray().toHexString()}")
    val read = CborRead(data)
    val readValue = read.value()
    assertEquals(value, readValue, "CBOR: ${data.toByteArray().toHexString()}")
}

fun skipTest(data: ByteData, value: CborValue) {
    data.resetForWriting(false)
    val cr = CborWrite(data)
    cr.value(value)
    val read = CborRead(data)
    val skipped = read.skipValue()
    assertTrue(skipped)
    val skippedEof = read.skipValue()
    assertFalse(skippedEof)
}

fun wr(write: CborWrite.() -> Unit, read: CborRead.() -> Unit) {
    val data = ByteData()
    write(CborWrite(data))
    read(CborRead(data))
}