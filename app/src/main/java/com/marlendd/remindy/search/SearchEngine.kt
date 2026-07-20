package com.marlendd.remindy.search

import com.marlendd.remindy.parse.TextNormalizer

/** Что ищем среди: предмет по имени, его алиасы (синонимы) и место. Всё уже нормализовано. */
data class SearchTarget(
    val id: Long,
    val nameNorm: String,
    val aliasNorms: List<String>,
    val locationNorm: String = "",
)

/** id предмета и его релевантность запросу (0..1). */
data class SearchMatch(val id: Long, val score: Double)

/**
 * Нечёткий поиск: нормализация → стоп-слова → стеммер → покрытие токенов запроса
 * (точная основа или добор Левенштейном) → ранжирование, топ-N.
 *
 * score = доля значимых слов запроса, нашедших совпадение в имени/алиасе предмета
 * или в его месте («паспорт в ящике» находит паспорт, лежащий в ящике). Хотя бы одно
 * слово обязано совпасть с именем/алиасом – запрос из одного места («ящик») не
 * возвращает всё содержимое ящика, а уходит в нулевой поиск. Порядок токенов не
 * важен, лишние слова в имени не штрафуют.
 */
class SearchEngine(
    private val stemmer: Stemmer,
    private val minScore: Double = 0.5,
    private val limit: Int = 5,
) {

    // Основа запроса → основы, которые она покрывает по встроенному тезаурусу.
    // Стеммим словарь тем же [stemmer]; строим один раз на первый поиск.
    private val expansions: Map<String, Set<String>> by lazy { SynonymDictionary.buildStemmed(stemmer) }

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
        val locationStems = stemsOf(target.locationNorm)
        val byName = coverage(queryStems, stemsOf(target.nameNorm), locationStems)
        val byAlias = target.aliasNorms
            .maxOfOrNull { coverage(queryStems, stemsOf(it), locationStems) } ?: 0.0
        return maxOf(byName, byAlias)
    }

    // Средняя сила совпадения слов запроса: точное = 1.0, нечёткое = 0.6.
    // Так точное совпадение всегда ранжируется выше нечёткого. Слово, не найденное
    // в имени, может закрыться местом, но без единого совпадения по имени/алиасу
    // покрытие = 0 (см. KDoc класса).
    private fun coverage(
        queryStems: List<String>,
        targetStems: List<String>,
        locationStems: List<String>,
    ): Double {
        if (queryStems.isEmpty() || targetStems.isEmpty()) return 0.0
        var sum = 0.0
        var nameHit = false
        for (q in queryStems) {
            val byName = targetStems.maxOfOrNull { t -> matchQuality(q, t) } ?: 0.0
            val byLocation = locationStems.maxOfOrNull { t -> matchQuality(q, t) } ?: 0.0
            if (byName > 0.0) nameHit = true
            sum += maxOf(byName, byLocation)
        }
        return if (nameHit) sum / queryStems.size else 0.0
    }

    // Насколько слово запроса `q` совпадает со словом цели `t` (направленно, q→t):
    // 1.0 – та же основа; SYN – связаны тезаурусом (документы→паспорт, телефон→мобильник);
    // FUZZY – опечатка в 1 правку, но только на длинных словах (>=5) с общим первым символом
    // (иначе короткие ложно совпадают: «мёд»≈«мел», «кот»≈«код», «лук»≈«люк»).
    // Порядок: точное > синоним > опечатка.
    private fun matchQuality(q: String, t: String): Double {
        if (q == t) return 1.0
        if (q.isEmpty() || t.isEmpty()) return 0.0
        if (expansions[q]?.contains(t) == true) return SYN
        if (minOf(q.length, t.length) < MIN_FUZZY_LEN) return 0.0
        if (q[0] != t[0]) return 0.0
        return if (Levenshtein.distance(q, t) <= 1) FUZZY else 0.0
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
        const val SYN = 0.9   // связь по тезаурусу: ниже точного (1.0), выше опечатки (0.6)
        const val FUZZY = 0.6
        const val MIN_FUZZY_LEN = 5
    }
}
