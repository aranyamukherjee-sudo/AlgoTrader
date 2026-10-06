package com.algotrader.discovery.reproducibility

import java.security.MessageDigest

internal object Hashing {
    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** First 12 hex characters of the SHA-256: short, stable identifiers. */
    fun short(text: String): String = sha256Hex(text).substring(0, 12)
}
