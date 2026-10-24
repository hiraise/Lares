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

    /** `task.assigned` (TSK-031). */
    fun taskAssigned(
        language: String,
        title: String,
        actor: String,
    ): Text =
        when (language) {
            "ru" -> Text("Вам поручена задача", "${short(title)} — от $actor")
            else -> Text("New task for you", "${short(title)} — from $actor")
        }

    /** `task.completed` (TSK-032). */
    fun taskCompleted(
        language: String,
        title: String,
        actor: String,
    ): Text =
        when (language) {
            "ru" -> Text("Задача выполнена", "${short(title)} — $actor")
            else -> Text("Task done", "${short(title)} — $actor")
        }

    /** `task.overdue` (TSK-033). */
    fun taskOverdue(
        language: String,
        title: String,
    ): Text =
        when (language) {
            "ru" -> Text("Задача просрочена", short(title))
            else -> Text("Task overdue", short(title))
        }

    /** Task titles are cut to [MAX_TITLE] characters, the last one being `…`, so the payload stays small. */
    fun short(title: String): String = if (title.length <= MAX_TITLE) title else title.take(MAX_TITLE - 1) + "…"

    const val MAX_TITLE = 100
}
