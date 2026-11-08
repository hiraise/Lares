package app.lares.client.ui.budget

/**
 * The one amount format of the budget (plan M5-04): whole rubles, digits grouped by threes with a narrow no-break
 * space (U+202F), negative with «−» (U+2212); [amount] adds a no-break space and «₽». No kopecks, not localized.
 */
object BudgetFormat {
    const val GROUP = ' '
    const val MINUS = '−'
    const val CURRENCY = " ₽"

    /** Longest amount a field accepts: nine digits always fit `Validators.BUDGET_AMOUNT_MAX` (10⁹). */
    const val MAX_DIGITS = 9

    /** «1 234 567», «−3 000». */
    fun digits(value: Long): String {
        val text = value.toString().removePrefix("-")
        val grouped =
            buildString {
                text.forEachIndexed { index, c ->
                    if (index > 0 && (text.length - index) % 3 == 0) append(GROUP)
                    append(c)
                }
            }
        return if (value < 0) "$MINUS$grouped" else grouped
    }

    /** «1 234 ₽». */
    fun amount(value: Long): String = digits(value) + CURRENCY

    /** What an amount field keeps of typed text: digits only, at most [MAX_DIGITS]. */
    fun input(text: String): String = text.filter { it in '0'..'9' }.take(MAX_DIGITS)

    /** The amount of a field; null when it is empty (= not recorded). */
    fun parse(text: String): Long? = input(text).takeIf { it.isNotEmpty() }?.toLong()
}
