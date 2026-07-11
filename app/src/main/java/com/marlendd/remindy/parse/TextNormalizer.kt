package com.marlendd.remindy.parse

/**
 * Нормализация текста для сравнения/поиска (поле name_norm, alias_norm).
 *
 * Приводит к нижнему регистру, заменяет ё→е, убирает пунктуацию и схлопывает
 * пробелы. Полноценный стеммер и Левенштейн – этап 3; здесь только базовая
 * форма, стабильная для сравнения дублей предмета.
 */
object TextNormalizer {

    private val NON_WORD = Regex("[^\\p{L}\\p{N}\\s]")
    private val WHITESPACE = Regex("\\s+")

    fun normalize(raw: String): String =
        raw.lowercase()
            .replace('ё', 'е')
            .replace(NON_WORD, " ")
            .replace(WHITESPACE, " ")
            .trim()
}
