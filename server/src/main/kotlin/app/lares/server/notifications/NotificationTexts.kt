package app.lares.server.notifications

/** Notification texts rendered on the server in the device's language (en, ru); English is the fallback. */
object NotificationTexts {
    val supportedLanguages = setOf("en", "ru")

    /** Normalizes a device language such as `ru-RU` to a supported one. */
    fun language(tag: String): String =
        tag
            .substringBefore('-')
            .substringBefore('_')
            .lowercase()
            .takeIf { it in supportedLanguages } ?: "en"

    data class Text(
        val title: String,
        val body: String,
    )

    fun systemTest(language: String): Text =
        when (language) {
            "ru" -> Text("Тестовое уведомление", "Мгновенные уведомления с семейного сервера работают.")
            else -> Text("Test notification", "Instant notifications from your family server work.")
        }
}
