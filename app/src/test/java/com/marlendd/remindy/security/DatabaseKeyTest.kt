package com.marlendd.remindy.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseKeyTest {

    private fun spec(vararg bytes: Int): String =
        String(DatabaseKey.rawKeySpec(bytes.map { it.toByte() }.toByteArray()), Charsets.US_ASCII)

    @Test fun formatsAsRawKeyLiteral() {
        assertEquals("x'00010f10abff'", spec(0x00, 0x01, 0x0f, 0x10, 0xab, 0xff))
    }

    @Test fun hexIsLowercase() {
        assertEquals("x'dead'", spec(0xde, 0xad))
    }

    @Test fun thirtyTwoBytesGive67AsciiBytes() {
        // 32 байта → x' + 64 hex + ' = 67 ASCII-символов; ровно то, что ждёт raw-key SQLCipher
        val key = ByteArray(32) { it.toByte() }
        val out = DatabaseKey.rawKeySpec(key)
        assertEquals(67, out.size)
        val s = String(out, Charsets.US_ASCII)
        assertTrue(s.startsWith("x'"))
        assertTrue(s.endsWith("'"))
        assertEquals(64, s.length - 3)
    }

    @Test fun highBitBytesUnsigned() {
        // 0x80 не должен стать отрицательным/знаковым мусором
        assertEquals("x'80'", spec(0x80))
    }
}
