package com.nutomic.syncthingandroid.service

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Streaming content hashing used only for dirty, metadata-ambiguous files. */
internal object ContentHasher {

    private const val BUFFER_SIZE = 32 * 1024

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                break
            }
            if (count > 0) {
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun sha256(file: File): String = file.inputStream().use(::sha256)
}
