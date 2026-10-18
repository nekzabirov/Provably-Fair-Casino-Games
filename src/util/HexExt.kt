package util

import java.security.MessageDigest

/** Lowercase hex encoding of raw bytes. */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** SHA-256 of the UTF-8 bytes of [input], hex-encoded. */
fun sha256(input: String): String =
    MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).toHex()
