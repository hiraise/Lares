package app.lares.contract.entities

import app.lares.contract.serialization.LenientEnumSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// Entity payloads of the shopping module (architecture/shopping.md § Contract data, ADR-0021). New fields are only ever appended.

/** A shopping list (SHP-001). Every member with `shopping.view` sees all lists of the household (Q-018). */
@Serializable
data class ShoppingListData(
    val name: String,
    /** Member id; set on create, never changes. */
    val createdBy: String,
    val tagIds: Set<String> = emptySet(),
    /** Items to buy are shown in sections by department (SHP-006). */
    val groupByCategory: Boolean = true,
)

/**
 * "This product in this list" (ADR-0021). Clearing and removing set [status] to [ShoppingItemStatus.CLEARED]; the row
 * stays as the list's memory (suggestions, no duplicates, remembered department).
 */
@Serializable
data class ShoppingItemData(
    /** Never changes. */
    val listId: String,
    /** Normalized: trimmed, inner whitespace collapsed (ShoppingNames.normalize). */
    val name: String,
    /** The member who added it to the list last (always the writer). */
    val addedBy: String,
    /** When it was added last; orders the list. */
    val addedAt: Instant,
    val status: ShoppingItemStatus = ShoppingItemStatus.NEEDED,
    val category: ProductCategory = ProductCategory.OTHER,
    /** Free text with unit: "2", "1,5 кг". */
    val quantity: String? = null,
    val note: String? = null,
    val tagIds: Set<String> = emptySet(),
    /** The member who ticked it last (always the writer). */
    val boughtBy: String? = null,
    /** Kept after clearing: ranks suggestions and drives "who is shopping". */
    val boughtAt: Instant? = null,
    /** Times ticked; ranks suggestions. */
    val boughtCount: Int = 0,
)

/** Unknown states from a newer version decode to [CLEARED]: the item is hidden rather than shown as something to buy. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = ShoppingItemStatusSerializer::class)
enum class ShoppingItemStatus {
    @SerialName("needed")
    NEEDED,

    @SerialName("bought")
    BOUGHT,

    @SerialName("cleared")
    CLEARED,
    ;

    /** The wire name, e.g. `needed`. */
    val key: String get() = ShoppingItemStatusSerializer.serialName(this)

    companion object {
        /** Returns the status with this wire name, or `null` if this version does not know it. */
        fun fromKey(key: String): ShoppingItemStatus? = ShoppingItemStatusSerializer.decodeOrNull(key)
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal object ShoppingItemStatusSerializer :
    LenientEnumSerializer<ShoppingItemStatus>(
        ShoppingItemStatus.generatedSerializer(),
        ShoppingItemStatus.entries,
        ShoppingItemStatus.CLEARED,
    )

/**
 * Store departments (SHP-006) in store order: the enum order is the display order. Unknown departments from a newer
 * version decode to [OTHER]. Labels are UI strings (`shopping_category_<key>`).
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = ProductCategorySerializer::class)
enum class ProductCategory {
    @SerialName("produce")
    PRODUCE,

    @SerialName("bakery")
    BAKERY,

    @SerialName("dairy")
    DAIRY,

    @SerialName("meat_fish")
    MEAT_FISH,

    @SerialName("grocery")
    GROCERY,

    @SerialName("sweets")
    SWEETS,

    @SerialName("drinks")
    DRINKS,

    @SerialName("frozen")
    FROZEN,

    @SerialName("household")
    HOUSEHOLD,

    @SerialName("personal_care")
    PERSONAL_CARE,

    @SerialName("baby")
    BABY,

    @SerialName("pets")
    PETS,

    @SerialName("pharmacy")
    PHARMACY,

    @SerialName("other")
    OTHER,
    ;

    /** The wire name, e.g. `meat_fish`. */
    val key: String get() = ProductCategorySerializer.serialName(this)

    companion object {
        /** Returns the department with this wire name, or `null` if this version does not know it. */
        fun fromKey(key: String): ProductCategory? = ProductCategorySerializer.decodeOrNull(key)
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal object ProductCategorySerializer :
    LenientEnumSerializer<ProductCategory>(ProductCategory.generatedSerializer(), ProductCategory.entries, ProductCategory.OTHER)
