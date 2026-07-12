package com.marlendd.remindy.security

/** Преобразование ключа базы в формат raw-key SQLCipher. Чистая логика – тестируется на JVM. */
object DatabaseKey {

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * 32-байтовый ключ → ASCII-строка `x'<64 hex>'` (67 байт).
     *
     * В таком виде SQLCipher берёт ключ КАК ЕСТЬ (raw key), минуя PBKDF2: наш ключ уже
     * высокоэнтропийный (из Keystore), деривация не нужна и лишь замедлила бы открытие.
     * Обычный `byte[]`-пароль SQLCipher, наоборот, прогнал бы через KDF.
     */
    fun rawKeySpec(passphrase: ByteArray): ByteArray {
        val hex = CharArray(passphrase.size * 2)
        for (i in passphrase.indices) {
            val v = passphrase[i].toInt() and 0xFF
            hex[i * 2] = HEX[v ushr 4]
            hex[i * 2 + 1] = HEX[v and 0x0F]
        }
        val spec = StringBuilder(hex.size + 3).append("x'").append(hex).append('\'')
        return spec.toString().toByteArray(Charsets.US_ASCII)
    }
}
