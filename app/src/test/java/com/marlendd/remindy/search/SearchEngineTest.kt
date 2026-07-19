package com.marlendd.remindy.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineTest {

    // Основы не приводим – морфологию в этих тестах ловит Левенштейн
    private val engine = SearchEngine(IdentityStemmer)

    private fun target(id: Long, name: String, vararg aliases: String) =
        SearchTarget(id, name, aliases.toList())

    @Test fun exactNameMatch() {
        val r = engine.search("ключи", listOf(target(1, "ключи"), target(2, "паспорт")))
        assertEquals(listOf(1L), r.map { it.id })
    }

    @Test fun dropsStopWords() {
        val r = engine.search("где лежат ключи", listOf(target(1, "ключи")))
        assertEquals(listOf(1L), r.map { it.id })
    }

    @Test fun typoOnLongWordMatches() {
        // Опечатка в 1 правку на длинном слове (общий первый символ) – совпадает
        val r = engine.search("сапоги", listOf(target(1, "сапаги")))
        assertEquals(listOf(1L), r.map { it.id })
    }

    @Test fun shortWordNoFalsePositive() {
        // «мёд»→«мед» (3 буквы) не должен ложно совпасть с «мел»
        val r = engine.search("мёд", listOf(target(1, "мел")))
        assertTrue(r.isEmpty())
    }

    @Test fun differentFirstLetterDoesNotFuzzyMatch() {
        val r = engine.search("зарядка", listOf(target(1, "нарядная")))
        assertTrue(r.isEmpty())
    }

    @Test fun twoWordQueryNeedsMoreThanOneToken() {
        // «зарядка телефон» не должен вернуть «телефон» (совпало 1 из 2 слов → 0.5)
        val r = engine.search("зарядка телефон", listOf(target(1, "телефон")))
        assertTrue(r.isEmpty())
    }

    @Test fun exactRanksAboveFuzzy() {
        val targets = listOf(
            target(1, "телефох"), // нечёткое (0.6)
            target(2, "телефон"), // точное (1.0)
        )
        val r = engine.search("телефон", targets)
        assertEquals(2L, r.first().id)
    }

    @Test fun multiTokenOrderIndependent() {
        val r = engine.search("гараж ключи", listOf(target(1, "ключи от гаража")))
        assertEquals(listOf(1L), r.map { it.id })
    }

    @Test fun synonymMatch() {
        val targets = listOf(target(1, "птс", "документы на машину"), target(2, "очки"))
        val r = engine.search("документы машина", targets)
        assertEquals(listOf(1L), r.map { it.id })
    }

    @Test fun zeroResultsBelowThreshold() {
        val r = engine.search("телевизор", listOf(target(1, "ключи"), target(2, "паспорт")))
        assertTrue(r.isEmpty())
    }

    @Test fun partialCoverageBelowHalfIsDropped() {
        // 1 из 3 слов совпало → 0.33 < 0.5 порога
        val r = engine.search("зелёный длинный шарф", listOf(target(1, "ключи")))
        assertTrue(r.isEmpty())
    }

    @Test fun limitsToFive() {
        val targets = (1..7L).map { target(it, "ключи") }
        val r = engine.search("ключи", targets)
        assertEquals(5, r.size)
    }

    @Test fun rankedByScoreDescending() {
        val targets = listOf(
            target(1, "ключи от гаража"), // «ключи гараж» покрыт полностью
            target(2, "ключи запасные"),  // покрыт наполовину
        )
        val r = engine.search("ключи гараж", targets)
        assertEquals(1L, r.first().id)
        assertTrue(r.first().score >= r.last().score)
    }

    @Test fun emptyQueryReturnsNothing() {
        assertTrue(engine.search("   ", listOf(target(1, "ключи"))).isEmpty())
    }

    // --- Место в запросе -------------------------------------------------------

    private fun targetAt(id: Long, name: String, location: String) =
        SearchTarget(id, name, emptyList(), location)

    @Test fun locationWordCompletesCoverage() {
        // «паспорт ящике»: имя закрывает 1 из 2 слов (0.5 – ниже порога), место добирает второе
        val r = engine.search("паспорт в ящике", listOf(targetAt(1, "паспорт", "в ящике стола")))
        assertEquals(listOf(1L), r.map { it.id })
    }

    @Test fun locationOnlyQueryDoesNotMatch() {
        // Запрос из одного места не должен вернуть всё, что там лежит – это нулевой поиск
        val r = engine.search("ящике", listOf(targetAt(1, "паспорт", "в ящике стола")))
        assertTrue(r.isEmpty())
    }

    @Test fun wrongLocationStillFailsThreshold() {
        // Имя совпало, но место в запросе чужое: 1 из 2 слов → 0.5, не проходит
        val r = engine.search("паспорт полке", listOf(targetAt(1, "паспорт", "в ящике стола")))
        assertTrue(r.isEmpty())
    }
}
