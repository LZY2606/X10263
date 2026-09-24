package heaplab

import java.security.MessageDigest

const val SCHEMA_VERSION = 1

fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
