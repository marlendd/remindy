package com.marlendd.remindy.search

import org.tartarus.snowball.ext.russianStemmer

/**
 * Русский стеммер Snowball (вендорен в org.tartarus.snowball, BSD-3). Приводит
 * словоформы к основе: «ключи»/«ключей»/«ключами» → одна основа, что даёт точное
 * совпадение в поиске вместо нечёткого.
 *
 * Экземпляр russianStemmer имеет изменяемое состояние и не потокобезопасен,
 * поэтому создаём новый на каждый вызов (Among-массивы статические, это дёшево).
 */
object RussianStemmer : Stemmer {
    override fun stem(word: String): String {
        if (word.isEmpty()) return word
        val stemmer = russianStemmer()
        stemmer.setCurrent(word)
        stemmer.stem()
        return stemmer.current
    }
}
