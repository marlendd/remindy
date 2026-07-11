package com.marlendd.remindy.parse

/** Результат разбора фразы на предмет и место. */
data class ParsedRecord(val item: String, val location: String)

/**
 * Делит распознанную фразу на предмет и место по первому предлогу.
 *
 * Правило (ТЗ F1): первая часть до предлога – предмет, остальное – место.
 * Предлог, по которому делим, отбрасывается; последующие предлоги внутри места
 * сохраняются («ПТС в ящике комода в спальне» → предмет «ПТС», место «ящике
 * комода в спальне»).
 *
 * Предлоги матчатся как ЦЕЛЫЕ слова, иначе «зарядка на столе» разделилось бы по
 * «за» внутри слова «зарядка».
 *
 * Если предлога нет или он стоит первым словом (предмет вышел бы пустым), вся
 * фраза уходит в предмет, место пустое.
 */
object UtteranceParser {

    // «во»/«подо» – озвонченные формы «в»/«под» (Vosk их так и выдаёт: «во втором ящике»)
    private val PREPOSITIONS = setOf("в", "во", "на", "под", "подо", "за")

    fun parse(raw: String): ParsedRecord {
        val tokens = raw.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ParsedRecord("", "")

        val prepositionIndex = tokens.indexOfFirst { it.lowercase() in PREPOSITIONS }
        // -1 (предлога нет) или 0 (предлог первый → предмет пуст) → вся фраза в предмет
        if (prepositionIndex <= 0) {
            return ParsedRecord(tokens.joinToString(" "), "")
        }

        val item = tokens.subList(0, prepositionIndex).joinToString(" ")
        val location = tokens.subList(prepositionIndex + 1, tokens.size).joinToString(" ")
        return ParsedRecord(item, location)
    }

    private val WHITESPACE = Regex("\\s+")
}
