package app.lares.contract.shopping

import app.lares.contract.validation.Validators

/** One item typed into the quick-add input. */
data class QuickAddEntry(
    val name: String,
    val quantity: String?,
)

/**
 * Turns the text typed into the quick-add input into items (SHP-003; architecture/shopping.md § Names and quick add):
 * entries split at line breaks, `;` and commas outside numbers; a quantity at the end of an entry is recognized.
 */
object QuickAdd {
    const val MAX_ENTRIES = 50

    private val number = Regex("\\d+(?:[.,]\\d+)?")
    private val numberWithUnit = Regex("(\\d+(?:[.,]\\d+)?)(\\D+)")
    private val times = Regex("[xх×](\\d+)|(\\d+)[xх×]")

    private val units: Set<String> =
        (
            "шт штук штуки штука кг г гр грамм л литр литра литров мл уп упак упаковка упаковки пач пачка пачки пачек бут " +
                "бутылка бутылки банка банки пакет пакета пакетов рулон рулона рулонов десяток pc pcs piece pieces kg g gr " +
                "l ml lb lbs oz pack packs bottle bottles can cans box boxes bag bags roll rolls dozen"
        ).split(' ').toSet()

    fun parse(text: String): List<QuickAddEntry> =
        split(text)
            .map(ShoppingNames::normalize)
            .filter { it.isNotEmpty() }
            .take(MAX_ENTRIES)
            .map(::entry)
            .filter { ShoppingNames.key(it.name).isNotEmpty() }

    /** Splits at line breaks, `;`, and `,` unless the comma has a digit on both sides. */
    private fun split(text: String): List<String> {
        val entries = mutableListOf<String>()
        val current = StringBuilder()
        for ((index, char) in text.withIndex()) {
            val separator =
                when (char) {
                    '\n', '\r', ';' -> true
                    ',' -> !(text.getOrNull(index - 1)?.isAsciiDigit() == true && text.getOrNull(index + 1)?.isAsciiDigit() == true)
                    else -> false
                }
            if (separator) {
                entries += current.toString()
                current.clear()
            } else {
                current.append(char)
            }
        }
        entries += current.toString()
        return entries
    }

    private fun entry(text: String): QuickAddEntry {
        val (name, quantity) = trailingQuantity(text) ?: (text to null)
        val parsed = if (quantity != null && name.any { it.isLetter() }) QuickAddEntry(name, quantity) else QuickAddEntry(text, null)
        return QuickAddEntry(
            name = ShoppingNames.displayName(parsed.name).take(Validators.SHOPPING_ITEM_NAME_MAX).trimEnd(),
            quantity = parsed.quantity?.take(Validators.SHOPPING_QUANTITY_MAX),
        )
    }

    /** The first matching rule: number + unit, a bare number, then `x2` / `2x`. Leading numbers are never parsed. */
    private fun trailingQuantity(text: String): Pair<String, String>? {
        val words = text.split(' ')
        if (words.size < 2) return null
        val last = words.last()
        val lower = last.lowercase()

        fun nameWithout(count: Int) = words.dropLast(count).joinToString(" ")

        if (words.size >= 3 && unit(lower) != null && number.matches(words[words.size - 2])) {
            return nameWithout(2) to "${words[words.size - 2]} ${unit(lower)}"
        }
        numberWithUnit.matchEntire(lower)?.let { match ->
            val unit = unit(match.groupValues[2])
            if (unit != null) return nameWithout(1) to "${last.substring(0, match.groupValues[1].length)} $unit"
        }
        if (number.matches(last)) return nameWithout(1) to last
        times.matchEntire(lower)?.let { match ->
            return nameWithout(1) to match.groupValues[1].ifEmpty { match.groupValues[2] }
        }
        return null
    }

    /** The unit in lower case if [word] (with an optional trailing dot) is one. */
    private fun unit(word: String): String? {
        val unit = word.removeSuffix(".")
        return unit.takeIf { it in units }
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}
