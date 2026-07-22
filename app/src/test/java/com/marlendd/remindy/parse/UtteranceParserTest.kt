package com.marlendd.remindy.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class UtteranceParserTest {

    private fun parse(s: String) = UtteranceParser.parse(s)

    @Test fun splitsAtFirstPreposition() {
        val r = parse("ПТС в верхнем ящике комода в спальне")
        assertEquals("ПТС", r.item)
        assertEquals("в верхнем ящике комода в спальне", r.location)
    }

    @Test fun prepositionInsideWordIsNotASeparator() {
        // «за» внутри «зарядка» не должно делить; делим по целому слову «на»
        val r = parse("зарядка на столе")
        assertEquals("зарядка", r.item)
        assertEquals("на столе", r.location)
    }

    @Test fun multiWordItem() {
        val r = parse("запасные ключи под ковриком")
        assertEquals("запасные ключи", r.item)
        assertEquals("под ковриком", r.location)
    }

    @Test fun prepositionZa() {
        val r = parse("паспорт за книгами")
        assertEquals("паспорт", r.item)
        assertEquals("за книгами", r.location)
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
        assertEquals("во втором ящике", r.location)
    }

    @Test fun voicedPrepositionPodo() {
        val r = parse("санки подо льдом")
        assertEquals("санки", r.item)
        assertEquals("подо льдом", r.location)
    }

    @Test fun caseInsensitivePreposition() {
        val r = parse("Документы На Полке")
        assertEquals("Документы", r.item)
        assertEquals("На Полке", r.location)
    }

    @Test fun collapsesExtraWhitespace() {
        val r = parse("  таблетки   в    тумбочке ")
        assertEquals("таблетки", r.item)
        assertEquals("в тумбочке", r.location)
    }

    @Test fun trailingPrepositionGivesEmptyLocation() {
        // предлог без слов после него местом не считается
        val r = parse("очки в")
        assertEquals("очки", r.item)
        assertEquals("", r.location)
    }

    @Test fun emptyInput() {
        val r = parse("   ")
        assertEquals("", r.item)
        assertEquals("", r.location)
    }

    @Test fun stripsTrailingPlacementVerb() {
        val r = parse("очки лежат на столе")
        assertEquals("очки", r.item)
        assertEquals("на столе", r.location)
    }

    @Test fun stripsTrailingVerbNaxoditsya() {
        val r = parse("паспорт находится в ящике")
        assertEquals("паспорт", r.item)
        assertEquals("в ящике", r.location)
    }

    @Test fun stripsTrailingVerbSpryatany() {
        val r = parse("деньги спрятаны под матрасом")
        assertEquals("деньги", r.item)
        assertEquals("под матрасом", r.location)
    }

    @Test fun stripsTrailingVerbForMultiWordItem() {
        val r = parse("запасные ключи висят на крючке")
        assertEquals("запасные ключи", r.item)
        assertEquals("на крючке", r.location)
    }

    @Test fun stripsTrailingVerbWithoutPreposition() {
        // «телефон лежит» без места: глагол отсекается, место пустое
        val r = parse("телефон лежит")
        assertEquals("телефон", r.item)
        assertEquals("", r.location)
    }

    @Test fun verbOnlyItemIsNotEmptied() {
        // глагол – единственное слово перед предлогом: не опустошаем предмет
        val r = parse("лежат на столе")
        assertEquals("лежат", r.item)
        assertEquals("на столе", r.location)
    }

    @Test fun stripsTrailingVerbCaseInsensitive() {
        val r = parse("Очки Лежат На Полке")
        assertEquals("Очки", r.item)
        assertEquals("На Полке", r.location)
    }

    // --- Активные глаголы «куда я это дел» -------------------------------------

    @Test fun stripsTrailingActionVerbPolozhu() {
        // Репортнутый случай: «положу» липло к предмету
        val r = parse("паспорт положу в комод")
        assertEquals("паспорт", r.item)
        assertEquals("в комод", r.location)
    }

    @Test fun stripsTrailingActionVerbUbral() {
        val r = parse("очки убрал в стол")
        assertEquals("очки", r.item)
        assertEquals("в стол", r.location)
    }

    @Test fun stripsTrailingActionVerbPovesil() {
        val r = parse("запасные ключи повесил на крючок")
        assertEquals("запасные ключи", r.item)
        assertEquals("на крючок", r.location)
    }

    @Test fun stripsTrailingActionVerbPolozhilaPast() {
        val r = parse("таблетки положила в тумбочку")
        assertEquals("таблетки", r.item)
        assertEquals("в тумбочку", r.location)
    }

    @Test fun stripsTrailingActionVerbKladu() {
        val r = parse("паспорт кладу в сумку")
        assertEquals("паспорт", r.item)
        assertEquals("в сумку", r.location)
    }

    @Test fun stripsTrailingActionVerbZasunul() {
        val r = parse("зарядку засунул за диван")
        assertEquals("зарядку", r.item)
        assertEquals("за диван", r.location)
    }

    @Test fun stripsActionVerbWithYoNormalization() {
        // «уберём» через ё нормализуется в «уберем» и тоже отсекается
        val r = parse("бельё уберём в шкаф")
        assertEquals("бельё", r.item)
        assertEquals("в шкаф", r.location)
    }

    @Test fun actionVerbOnlyItemIsNotEmptied() {
        // глагол-действие – единственное слово перед предлогом: предмет не опустошаем
        val r = parse("положу в комод")
        assertEquals("положу", r.item)
        assertEquals("в комод", r.location)
    }

    // --- Глагол В НАЧАЛЕ фразы («положил паспорт в комод») ----------------------

    @Test fun stripsLeadingActionVerb() {
        // Репортнутый случай: глагол первым словом
        val r = parse("положил паспорт в комод")
        assertEquals("паспорт", r.item)
        assertEquals("в комод", r.location)
    }

    @Test fun stripsLeadingVerbUbral() {
        val r = parse("убрал очки в стол")
        assertEquals("очки", r.item)
        assertEquals("в стол", r.location)
    }

    @Test fun stripsLeadingVerbWithoutPreposition() {
        // без места: «убрала зарядку» → предмет «зарядку», факт
        val r = parse("убрала зарядку")
        assertEquals("зарядку", r.item)
        assertEquals("", r.location)
    }

    @Test fun stripsLeadingVerbMultiWordItem() {
        val r = parse("повесил запасные ключи на крючок")
        assertEquals("запасные ключи", r.item)
        assertEquals("на крючок", r.location)
    }
}
