package app.lares.contract

/**
 * Setup, invite and login codes (architecture/auth.md § Codes): Crockford base32, shown with a dash in the middle.
 * Codes are generated only on the server; both sides normalize user input the same way.
 */
object Codes {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Uppercase, drop spaces and dashes, map the look-alikes `O→0`, `I→1`, `L→1`. */
    fun normalize(input: String): String =
        buildString {
            for (char in input.uppercase()) {
                when (char) {
                    ' ', '-', '\t', '\n', '\r' -> Unit
                    'O' -> append('0')
                    'I', 'L' -> append('1')
                    else -> append(char)
                }
            }
        }

    /** Display form: `ABCDE-FGHJK`. */
    fun format(code: String): String {
        val half = code.length / 2
        return if (code.length < 2) code else code.substring(0, half) + "-" + code.substring(half)
    }
}
