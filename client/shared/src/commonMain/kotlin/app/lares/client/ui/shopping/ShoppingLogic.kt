package app.lares.client.ui.shopping

import app.lares.client.data.MemberView
import app.lares.client.data.Tag
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
import app.lares.client.resources.shopping_item_added
import app.lares.client.resources.shopping_item_bought
import app.lares.client.resources.shopping_item_bought_times
import app.lares.client.resources.shopping_meta_pair
import app.lares.client.resources.shopping_names_more
import app.lares.client.resources.shopping_offline_pending
import app.lares.client.resources.shopping_offline_stale
import app.lares.client.resources.shopping_progress
import app.lares.client.resources.shopping_remaining
import app.lares.client.resources.shopping_shopper_now
import app.lares.client.resources.shopping_shopper_short
import app.lares.client.resources.tag_shopping_left
import app.lares.client.resources.today_shopping_others
import app.lares.client.sync.SyncState
import app.lares.client.sync.SyncStatus
import app.lares.client.ui.common.UiText
import app.lares.client.ui.common.raw
import app.lares.client.ui.common.uiText
import app.lares.client.ui.main.TodayShoppingUi
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

    /** «6 из 9» on the Today card. */
    fun countOf(card: TodayShoppingUi): UiText =
        uiText(Res.string.shopping_count_of, raw(card.bought.toString()), raw(card.total.toString()))

    /** «Осталось: Молоко, Хлеб, Яблоки и ещё 2» on the Today card. */
    fun remaining(card: TodayShoppingUi): UiText =
        uiText(Res.string.shopping_remaining, names(card.remaining, card.remaining.size + card.more))

    /** «Дмитрий сейчас в магазине» on the Today card. */
    fun shopperNow(member: MemberView): UiText = uiText(Res.string.shopping_shopper_now, raw(firstName(member)))

    /** «Ещё 2 списка» under the Today card. */
    fun others(count: Int): UiText = UiText.Plural(Res.plurals.today_shopping_others, count, listOf(raw(count.toString())))

    /** «Осталось 3» on a list row of the tag page. */
    fun left(list: ShoppingListView): UiText = uiText(Res.string.tag_shopping_left, raw(list.needed.toString()))

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
    ): UiText? = pair(item.boughtBy, item.data.boughtAt, time)

    /** «Добавлено: Ольга · 10:15» in the item sheet. */
    fun addedLine(
        item: ShoppingItemView,
        time: (Instant) -> String,
    ): UiText? = pair(item.addedBy, item.data.addedAt, time)?.let { uiText(Res.string.shopping_item_added, it) }

    /** «Куплено: Дмитрий · 23 октября, 18:40» for an item ever bought. */
    fun boughtLine(
        item: ShoppingItemView,
        time: (Instant) -> String,
    ): UiText? =
        item.data.boughtAt
            ?.let { pair(item.boughtBy, it, time) }
            ?.let { uiText(Res.string.shopping_item_bought, it) }

    /** «Покупали 12 раз»; null unless bought more than once. */
    fun boughtTimes(item: ShoppingItemView): UiText? =
        item.data.boughtCount.takeIf { it > 1 }?.let {
            UiText.Plural(Res.plurals.shopping_item_bought_times, it, listOf(raw(it.toString())))
        }

    /** Tags a picker offers: active ones and archived ones already [selected], by name. */
    fun tagChoices(
        tags: List<Tag>,
        selected: Set<String>,
    ): List<Tag> = tags.filter { !it.data.archived || it.id in selected }.sortedBy { it.data.name.lowercase() }

    /** «Ольга · 10:15», or whichever of the two is known. */
    private fun pair(
        member: MemberView?,
        at: Instant?,
        time: (Instant) -> String,
    ): UiText? {
        val who = member?.let(::firstName)
        val formatted = at?.let(time)
        return when {
            who != null && formatted != null -> uiText(Res.string.shopping_meta_pair, raw(who), raw(formatted))
            else -> (who ?: formatted)?.let(::raw)
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
