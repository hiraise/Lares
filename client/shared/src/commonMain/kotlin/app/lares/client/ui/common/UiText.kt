package app.lares.client.ui.common

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * A localized text described by resources, built by pure logic (e.g. `TasksLogic`) and resolved in the app language:
 * [text] in composition (follows language changes), [load] outside it (tests, notifications).
 */
sealed interface UiText {
    /** Text that needs no translation: names, times, numbers. */
    data class Raw(
        val value: String,
    ) : UiText

    data class Str(
        val res: StringResource,
        val args: List<UiText> = emptyList(),
    ) : UiText

    data class Plural(
        val res: PluralStringResource,
        val quantity: Int,
        val args: List<UiText> = emptyList(),
    ) : UiText

    /** One item of a string array, e.g. a month name. */
    data class Item(
        val res: StringArrayResource,
        val index: Int,
    ) : UiText

    /** [parts] joined with [separator] (a neutral one like " · " or ", "). */
    data class Concat(
        val parts: List<UiText>,
        val separator: String,
    ) : UiText

    /** [text] with its first letter in upper case (a chip starting with «пн»). */
    data class Capitalized(
        val text: UiText,
    ) : UiText
}

fun uiText(
    res: StringResource,
    vararg args: UiText,
): UiText = UiText.Str(res, args.toList())

fun raw(value: String): UiText = UiText.Raw(value)

@Composable
fun UiText.text(): String =
    when (this) {
        is UiText.Raw -> value
        is UiText.Str -> stringResource(res, *args.map { it.text() }.toTypedArray())
        is UiText.Plural -> pluralStringResource(res, quantity, *args.map { it.text() }.toTypedArray())
        is UiText.Item -> stringArrayResource(res)[index]
        is UiText.Concat -> parts.map { it.text() }.joinToString(separator)
        is UiText.Capitalized -> text.text().replaceFirstChar { it.uppercaseChar() }
    }

suspend fun UiText.load(): String =
    when (this) {
        is UiText.Raw -> value
        is UiText.Str -> getString(res, *args.map { it.load() }.toTypedArray())
        is UiText.Plural -> getPluralString(res, quantity, *args.map { it.load() }.toTypedArray())
        is UiText.Item -> getStringArray(res)[index]
        is UiText.Concat -> parts.map { it.load() }.joinToString(separator)
        is UiText.Capitalized -> text.load().replaceFirstChar { it.uppercaseChar() }
    }
