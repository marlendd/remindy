package com.marlendd.remindy.search

import org.junit.Assert.assertEquals
import org.junit.Test

class RussianStemmerTest {

    private fun stem(w: String) = RussianStemmer.stem(w)

    @Test fun reducesNounDeclensionsToSameStem() {
        assertEquals(stem("ключ"), stem("ключи"))
        assertEquals(stem("ключ"), stem("ключей"))
        assertEquals(stem("документ"), stem("документы"))
        assertEquals(stem("паспорт"), stem("паспорта"))
    }

    @Test fun idempotentOnStem() {
        val once = stem("ящике")
        assertEquals(once, stem(once))
    }

    @Test fun emptyStaysEmpty() {
        assertEquals("", stem(""))
    }

    @Test
    fun searchMatchesDeclensionViaStemmer() {
        // С настоящим стеммером «где мои ключи» находит предмет «ключей»
        val engine = SearchEngine(RussianStemmer)
        val r = engine.search("где мои ключи", listOf(SearchTarget(1, "ключей", emptyList())))
        assertEquals(listOf(1L), r.map { it.id })
    }
}
