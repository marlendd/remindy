package com.marlendd.remindy.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class UtteranceParserTest {

    private fun parse(s: String) = UtteranceParser.parse(s)

    @Test fun splitsAtFirstPreposition() {
        val r = parse("ПТС в верхнем ящике комода в спальне")
        assertEquals("ПТС", r.item)
        assertEquals("верхнем ящике комода в спальне", r.location)
    }

    @Test fun prepositionInsideWordIsNotASeparator() {
        // «за» внутри «зарядка» не должно делить; делим по целому слову «на»
        val r = parse("зарядка на столе")
        assertEquals("зарядка", r.item)
        assertEquals("столе", r.location)
    }

    @Test fun multiWordItem() {
        val r = parse("запасные ключи под ковриком")
        assertEquals("запасные ключи", r.item)
        assertEquals("ковриком", r.location)
    }

    @Test fun prepositionZa() {
        val r = parse("паспорт за книгами")
        assertEquals("паспорт", r.item)
        assertEquals("книгами", r.location)
    }

    @Test fun noPrepositionPutsAllIntoItem() {
        val r = parse("очки")
        assertEquals("очки", r.item)
        assertEquals("", r.location)
    }

    @Test fun prepositionAsFirstWordKeepsWholePhraseAsItem() {
        // предмет вышел бы пустым → вся фраза в предмет
        val r = parse("на всякий случай")
        assertEquals("на всякий случай", r.item)
        assertEquals("", r.location)
    }

    @Test fun voicedPrepositionVo() {
        val r = parse("деньги во втором ящике")
        assertEquals("деньги", r.item)
        assertEquals("втором ящике", r.location)
    }

    @Test fun voicedPrepositionPodo() {
        val r = parse("санки подо льдом")
        assertEquals("санки", r.item)
        assertEquals("льдом", r.location)
    }

    @Test fun caseInsensitivePreposition() {
        val r = parse("Документы На Полке")
        assertEquals("Документы", r.item)
        assertEquals("Полке", r.location)
    }

    @Test fun collapsesExtraWhitespace() {
        val r = parse("  таблетки   в    тумбочке ")
        assertEquals("таблетки", r.item)
        assertEquals("тумбочке", r.location)
    }

    @Test fun trailingPrepositionGivesEmptyLocation() {
        val r = parse("очки в")
        assertEquals("очки", r.item)
        assertEquals("", r.location)
    }

    @Test fun emptyInput() {
        val r = parse("   ")
        assertEquals("", r.item)
        assertEquals("", r.location)
    }
}
