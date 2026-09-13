package com.nutomic.syncthingandroid.service

import java.io.ByteArrayInputStream

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentHasherTest {

    @Test
    fun sha256_isStreamingAndStable() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ContentHasher.sha256(ByteArrayInputStream("abc".toByteArray())),
        )
    }
}
