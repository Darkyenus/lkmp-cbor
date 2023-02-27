package com.darkyen.cbor

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe


fun passthroughTest(data: ByteData, value: CborValue) {
    data.resetForWriting(false)
    val write = CborWrite(data)
    write.value(value)
    //println("$value -> ${data.toByteArray().toHexString()}")
    val read = CborRead(data)
    val readValue = read.value()
    withClue({"CBOR: ${data.toByteArray().toHexString()}"}) {
        readValue shouldBe value
    }
}

fun skipTest(data: ByteData, value: CborValue) {
    data.resetForWriting(false)
    val cr = CborWrite(data)
    cr.value(value)
    val read = CborRead(data)
    val skipped = read.skipValue()
    skipped.shouldBeTrue()
    val skippedEof = read.skipValue()
    skippedEof.shouldBeFalse()
}

fun wr(write: CborWrite.() -> Unit, read: CborRead.() -> Unit) {
    val data = ByteData()
    write(CborWrite(data))
    read(CborRead(data))
}