package app.lares.client.ui.shopping

import app.lares.client.data.MemberView
import app.lares.client.data.shopping.ShoppingItemView
import app.lares.client.data.shopping.ShoppingListView
import app.lares.client.resources.Res
import app.lares.client.resources.shopping_all_bought
import app.lares.client.resources.shopping_already_there
import app.lares.client.resources.shopping_bought_count
import app.lares.client.resources.shopping_card_empty
import app.lares.client.resources.shopping_category_baby
import app.lares.client.resources.shopping_category_bakery
import app.lares.client.resources.shopping_category_dairy
import app.lares.client.resources.shopping_category_drinks
import app.lares.client.resources.shopping_category_frozen
import app.lares.client.resources.shopping_category_grocery
import app.lares.client.resources.shopping_category_household
import app.lares.client.resources.shopping_category_meat_fish
import app.lares.client.resources.shopping_category_other
import app.lares.client.resources.shopping_category_personal_care
import app.lares.client.resources.shopping_category_pets
import app.lares.client.resources.shopping_category_pharmacy
import app.lares.client.resources.shopping_category_produce
import app.lares.client.resources.shopping_category_sweets
import app.lares.client.resources.shopping_cleared
import app.lares.client.resources.shopping_count_of
import app.lares.client.resources.shopping_meta_pair
import app.lares.client.resources.shopping_names_more
import app.lares.client.resources.shopping_offline_pending
import app.lares.client.resources.shopping_offline_stale
import app.lares.client.resources.shopping_progress
import app.lares.client.resources.shopping_remaining
import app.lares.client.resources.shopping_shopper_now
import app.lares.client.resources.shopping_shopper_short
import app.lares.client.sync.SyncState
import app.lares.client.sync.SyncStatus
import app.lares.client.ui.common.UiText
import app.lares.client.ui.common.raw
import app.lares.client.ui.common.uiText
import app.lares.contract.entities.ProductCategory
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Instant

/** The last line of a list card: what is left, "all bought" (shown in `success`) or "empty". */
data class CardLine(
    val text: UiText,
    val done: Boolean,
)

/** Pure texts of the shopping screens (plan M2-04); resolved with `text()` in composition or `load()` in tests. */
object ShoppingLogic {
    /** Names shown before "and N more" in previews. */
    const val PREVIEW_NAMES = 3

    /** First word of the member's name ("Дмитрий Петров" → "Дмитрий"). */
    fun firstName(member: MemberView): String =
        member.name
            .trim()
            .split(WHITESPACE)
            .first()

    /** Items in the progress: bought of bought + to buy. */
    fun total(list: ShoppingListView): Int = list.needed + list.bought

    /** Progress bar fill, 0..1; 0 for an empty list. */
    fun fraction(list: ShoppingListView): Float = if (total(list) == 0) 0f else list.bought.toFloat() / total(list)

    /** «6 из 9» on a card; null when the list has nothing to buy and nothing bought. */
    fun countOf(list: ShoppingListView): UiText? =
        if (total(list) == 0) null else uiText(Res.string.shopping_count_of, raw(list.bought.toString()), raw(total(list).toString()))

    /** «Куплено 6 из 9» on the list page; null when the list has nothing to buy and nothing bought. */
    fun progress(list: ShoppingListView): UiText? =
        if (total(list) == 0) null else uiText(Res.string.shopping_progress, raw(list.bought.toString()), raw(total(list).toString()))

    /** «Осталось: Молоко, Хлеб, Яблоки и ещё 2», «Всё куплено» or «Пусто». */
    fun cardLine(list: ShoppingListView): CardLine =
        when {
            list.needed > 0 -> CardLine(uiText(Res.string.shopping_remaining, names(list.remaining, list.needed)), done = false)
            list.bought > 0 -> CardLine(uiText(Res.string.shopping_all_bought), done = true)
            else -> CardLine(uiText(Res.string.shopping_card_empty), done = false)
        }

    /** «Дмитрий сейчас в магазине» (cards, Today); null when nobody is shopping. */
    fun shopperNow(list: ShoppingListView): UiText? = list.shopper?.let { uiText(Res.string.shopping_shopper_now, raw(firstName(it))) }

    /** «Дмитрий в магазине» (the narrow progress row of the list page). */
    fun shopperShort(list: ShoppingListView): UiText? = list.shopper?.let { uiText(Res.string.shopping_shopper_short, raw(firstName(it))) }

    /** «Куплено · 6». */
    fun boughtTitle(count: Int): UiText = uiText(Res.string.shopping_bought_count, raw(count.toString()))

    /** «Творог, Яйца, Сыр и ещё 3»: the first names of [bought] (newest tick first) and how many more. */
    fun boughtPreview(bought: List<ShoppingItemView>): UiText = names(bought.take(PREVIEW_NAMES).map { it.data.name }, bought.size)

    /** «Дмитрий · 18:40» under a bought row; [time] formats the tick time. */
    fun boughtMeta(
        item: ShoppingItemView,
        time: (Instant) -> String,
    ): UiText? {
        val who = item.boughtBy?.let(::firstName)
        val at = item.data.boughtAt?.let(time)
        return when {
            who != null && at != null -> uiText(Res.string.shopping_meta_pair, raw(who), raw(at))
            else -> (who ?: at)?.let(::raw)
        }
    }

    /** Who added the item, for its meta line; null when it was me (or the member is unknown). */
    fun adder(item: ShoppingItemView): String? = if (item.mine) null else item.addedBy?.let(::firstName)

    /** «Уже в списке: Молоко, Хлеб». */
    fun alreadyThere(names: List<String>): UiText = uiText(Res.string.shopping_already_there, joined(names))

    /** «Убрано: 6». */
    fun cleared(count: Int): UiText = UiText.Plural(Res.plurals.shopping_cleared, count, listOf(raw(count.toString())))

    /** The in-store banner (SHP-011, FND-043); null while online. */
    fun banner(status: SyncStatus?): UiText? =
        when {
            status?.state != SyncState.OFFLINE -> null
            status.pendingCount > 0 -> uiText(Res.string.shopping_offline_pending)
            else -> uiText(Res.string.shopping_offline_stale)
        }

    fun categoryLabel(category: ProductCategory): StringResource =
        when (category) {
            ProductCategory.PRODUCE -> Res.string.shopping_category_produce
            ProductCategory.BAKERY -> Res.string.shopping_category_bakery
            ProductCategory.DAIRY -> Res.string.shopping_category_dairy
            ProductCategory.MEAT_FISH -> Res.string.shopping_category_meat_fish
            ProductCategory.GROCERY -> Res.string.shopping_category_grocery
            ProductCategory.SWEETS -> Res.string.shopping_category_sweets
            ProductCategory.DRINKS -> Res.string.shopping_category_drinks
            ProductCategory.FROZEN -> Res.string.shopping_category_frozen
            ProductCategory.HOUSEHOLD -> Res.string.shopping_category_household
            ProductCategory.PERSONAL_CARE -> Res.string.shopping_category_personal_care
            ProductCategory.BABY -> Res.string.shopping_category_baby
            ProductCategory.PETS -> Res.string.shopping_category_pets
            ProductCategory.PHARMACY -> Res.string.shopping_category_pharmacy
            ProductCategory.OTHER -> Res.string.shopping_category_other
        }

    /** [shown] names (as stored) and "and N more" when [total] is larger. */
    private fun names(
        shown: List<String>,
        total: Int,
    ): UiText {
        val more = total - shown.size
        return if (more >
            0
        ) {
            UiText.Plural(Res.plurals.shopping_names_more, more, listOf(joined(shown), raw(more.toString())))
        } else {
            joined(shown)
        }
    }

    private fun joined(names: List<String>): UiText = UiText.Concat(names.map(::raw), ", ")

    private val WHITESPACE = Regex("\\s+")
}
