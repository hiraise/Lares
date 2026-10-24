package app.lares.contract.shopping

/** Item names (architecture/shopping.md § Names and quick add). Pure; no platform locale. */
object ShoppingNames {
    private val whitespace = Regex("\\s+")

    /** Trims and collapses runs of whitespace to one space. Stored item names are normalized. */
    fun normalize(name: String): String = name.trim().replace(whitespace, " ")

    /**
     * The match key: lower case, `ё` → `е`, every character that is not a letter or digit → space, spaces collapsed.
     * Two items match iff their keys are equal (`"Молоко 2,5%"` → `"молоко 2 5"`). Never sent over the wire.
     */
    fun key(name: String): String {
        val text = name.lowercase().replace('ё', 'е')
        val builder = StringBuilder(text.length)
        var pendingSpace = false
        for (char in text) {
            if (char.isLetterOrDigit()) {
                if (pendingSpace && builder.isNotEmpty()) builder.append(' ')
                pendingSpace = false
                builder.append(char)
            } else {
                pendingSpace = true
            }
        }
        return builder.toString()
    }

    /** Upper-cases the first character if the first word has no upper-case letter (`"молоко"` → `"Молоко"`). */
    fun displayName(name: String): String {
        if (name.isEmpty()) return name
        val firstWord = name.substringBefore(' ')
        if (firstWord.any { it.isUpperCase() }) return name
        return name.first().uppercase() + name.substring(1)
    }
}
