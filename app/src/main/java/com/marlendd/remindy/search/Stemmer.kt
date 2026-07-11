package com.marlendd.remindy.search

/** Приведение слова к основе для нечёткого сравнения. */
fun interface Stemmer {
    fun stem(word: String): String
}

/** Заглушка: возвращает слово как есть. Используется в тестах и как fallback. */
object IdentityStemmer : Stemmer {
    override fun stem(word: String): String = word
}
