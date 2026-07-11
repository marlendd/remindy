package com.marlendd.remindy.search

import com.marlendd.remindy.parse.TextNormalizer

/** Что ищем среди: предмет по имени и его алиасы (синонимы). Нормы уже нормализованы. */
data class SearchTarget(
    val id: Long,
    val nameNorm: String,
    val aliasNorms: List<String>,
)

/** id предмета и его релевантность запросу (0..1). */
data class SearchMatch(val id: Long, val score: Double)

/**
 * Нечёткий поиск: нормализация → стоп-слова → стеммер → покрытие токенов запроса
 * (точная основа или добор Левенштейном) → ранжирование, топ-N.
 *
 * score = доля значимых слов запроса, нашедших совпадение в имени или алиасе
 * предмета. Порядок токенов не важен, лишние слова в имени не штрафуют.
 */
class SearchEngine(
    private val stemmer: Stemmer,
    private val minScore: Double = 0.5,
    private val limit: Int = 5,
) {

    fun search(rawQuery: String, targets: List<SearchTarget>): List<SearchMatch> {
        val queryStems = contentStems(rawQuery)
        if (queryStems.isEmpty()) return emptyList()

        return targets
            .map { SearchMatch(it.id, score(queryStems, it)) }
            // строго больше: двусловный запрос по одному слову (0.5) не проходит
            .filter { it.score > minScore }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun score(queryStems: List<String>, target: SearchTarget): Double {
        val byName = coverage(queryStems, stemsOf(target.nameNorm))
        val byAlias = target.aliasNorms.maxOfOrNull { coverage(queryStems, stemsOf(it)) } ?: 0.0
        return maxOf(byName, byAlias)
    }

    // Средняя сила совпадения слов запроса: точное = 1.0, нечёткое = 0.6.
    // Так точное совпадение всегда ранжируется выше нечёткого.
    private fun coverage(queryStems: List<String>, targetStems: List<String>): Double {
        if (queryStems.isEmpty() || targetStems.isEmpty()) return 0.0
        val sum = queryStems.sumOf { q ->
            targetStems.maxOfOrNull { t -> matchQuality(q, t) } ?: 0.0
        }
        return sum / queryStems.size
    }

    // 1.0 – та же основа; FUZZY – опечатка в 1 правку, но только на длинных словах
    // (>=5) с общим первым символом. Иначе короткие слова ложно совпадают:
    // «мёд»≈«мел», «кот»≈«код», «лук»≈«люк».
    private fun matchQuality(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (minOf(a.length, b.length) < MIN_FUZZY_LEN) return 0.0
        if (a[0] != b[0]) return 0.0
        return if (Levenshtein.distance(a, b) <= 1) FUZZY else 0.0
    }

    private fun contentStems(raw: String): List<String> {
        val tokens = TextNormalizer.normalize(raw).split(' ').filter { it.isNotBlank() }
        // Если после выкидывания стоп-слов ничего не осталось – ищем по всем словам
        val content = tokens.filterNot { it in StopWords.RU }
        val chosen = content.ifEmpty { tokens }
        return chosen.map { stemmer.stem(it) }
    }

    private fun stemsOf(norm: String): List<String> =
        norm.split(' ').filter { it.isNotBlank() }.map { stemmer.stem(it) }

    private companion object {
        const val FUZZY = 0.6
        const val MIN_FUZZY_LEN = 5
    }
}
