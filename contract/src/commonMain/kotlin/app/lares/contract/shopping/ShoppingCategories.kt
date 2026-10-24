package app.lares.contract.shopping

import app.lares.contract.entities.ProductCategory

/**
 * Guesses the store department of a product name from the built-in [CategoryDictionary] (SHP-006;
 * architecture/shopping.md § Departments). Pure and deterministic. The household's own choices take precedence; this
 * is only the fallback for names the family has never sorted.
 */
object ShoppingCategories {
    private val prepositions =
        setOf("для", "от", "из", "с", "со", "без", "в", "во", "на", "под", "к", "по", "for", "with", "without", "from", "of", "in", "on")
    private val adjectiveEndings = listOf("ый", "ий", "ой", "ая", "яя", "ое", "ее", "ые", "ие", "ую", "юю", "ого", "его", "ым", "им")

    private class Phrase(
        val stems: List<String>,
        val category: ProductCategory,
    ) {
        val length = stems.sumOf { it.length }
    }

    private val stemIndex: Map<String, ProductCategory> by lazy {
        CategoryDictionary.stems.flatMap { (category, stems) -> stems.map { it to category } }.toMap()
    }
    private val longestStem: Int by lazy { stemIndex.keys.maxOf { it.length } }
    private val phrases: List<Phrase> by lazy {
        CategoryDictionary.phrases
            .flatMap { (category, phrases) -> phrases.map { Phrase(it.split(' '), category) } }
            .sortedByDescending { it.length }
    }
    private val modifiers: List<Pair<String, ProductCategory>> by lazy {
        CategoryDictionary.modifiers.flatMap { (category, stems) -> stems.map { it to category } }
    }

    fun guess(name: String): ProductCategory {
        val words = ShoppingNames.key(name).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return ProductCategory.OTHER
        phraseCategory(words)?.let { return it }
        modifierCategory(words)?.let { return it }
        val split = words.indexOfFirst { it in prepositions }
        val head = if (split < 0) words else words.subList(0, split)
        val tail = if (split < 0) emptyList() else words.subList(split + 1, words.size)
        return wordCategory(head) ?: wordCategory(tail) ?: ProductCategory.OTHER
    }

    private fun phraseCategory(words: List<String>): ProductCategory? =
        phrases
            .firstOrNull { phrase ->
                (0..words.size - phrase.stems.size).any { start ->
                    phrase.stems.withIndex().all { (offset, stem) -> stemMatches(stem, words[start + offset]) }
                }
            }?.category

    private fun modifierCategory(words: List<String>): ProductCategory? =
        words.firstNotNullOfOrNull { word -> modifiers.firstOrNull { (stem, _) -> word.startsWith(stem) }?.second }

    /** The last matching word that does not look like a Russian adjective (else the last adjective) decides. */
    private fun wordCategory(words: List<String>): ProductCategory? {
        val matches = words.mapNotNull { word -> stemCategory(word)?.let { word to it } }
        val nouns = matches.filterNot { (word, _) -> looksLikeAdjective(word) }
        return nouns.ifEmpty { matches }.lastOrNull()?.second
    }

    /** The category of the longest stem matching [word]. */
    private fun stemCategory(word: String): ProductCategory? {
        val longest = minOf(word.length, longestStem)
        for (length in longest downTo maxOf(MIN_STEM, word.length - MAX_EXTRA)) {
            if (length <= SHORT_STEM && word.length - length > SHORT_EXTRA) continue
            stemIndex[word.substring(0, length)]?.let { return it }
        }
        return null
    }

    private fun stemMatches(
        stem: String,
        word: String,
    ): Boolean {
        val extra = word.length - stem.length
        return word.startsWith(stem) && extra <= if (stem.length <= SHORT_STEM) SHORT_EXTRA else MAX_EXTRA
    }

    private fun looksLikeAdjective(word: String): Boolean =
        word.length >= 5 && word.all { it in 'а'..'я' } && adjectiveEndings.any { word.endsWith(it) }

    private const val MIN_STEM = 3
    private const val SHORT_STEM = 3
    private const val SHORT_EXTRA = 1
    private const val MAX_EXTRA = 3
}
