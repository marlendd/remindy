package com.marlendd.remindy.security

/** Преобразование ключа базы в формат raw-key SQLCipher. Чистая логика – тестируется на JVM. */
object DatabaseKey {

    private val HEX = "0123456789abcdef".toByteArray(Charsets.US_ASCII)
    private val QUOTE = '\''.code.toByte()

    /**
     * 32-байтовый ключ → ASCII-байты `x'<64 hex>'` (67 байт).
     *
     * В таком виде SQLCipher берёт ключ КАК ЕСТЬ (raw key), минуя PBKDF2: наш ключ уже
     * высокоэнтропийный (из Keystore), деривация не нужна и лишь замедлила бы открытие.
     * Обычный `byte[]`-пароль SQLCipher, наоборот, прогнал бы через KDF.
     *
     * Байты собираем напрямую, БЕЗ промежуточного String: строка с hex полного ключа
     * зависла бы в куче до GC, и занулить её нельзя.
     */
    fun rawKeySpec(passphrase: ByteArray): ByteArray {
        val out = ByteArray(passphrase.size * 2 + 3)
        out[0] = 'x'.code.toByte()
        out[1] = QUOTE
        for (i in passphrase.indices) {
            val v = passphrase[i].toInt() and 0xFF
            out[2 + i * 2] = HEX[v ushr 4]
            out[3 + i * 2] = HEX[v and 0x0F]
        }
        out[out.lastIndex] = QUOTE
        return out
    }
}
