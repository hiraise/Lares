package app.lares.client.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import app.lares.client.data.MemberView
import app.lares.client.data.Tag
import app.lares.client.data.shopping.ShoppingInputs
import app.lares.client.data.shopping.ShoppingViews
import app.lares.client.data.shopping.Suggestion
import app.lares.client.resources.Res
import app.lares.client.resources.shopping_offline_pending
import app.lares.client.ui.common.uiText
import app.lares.client.ui.shopping.ItemForm
import app.lares.client.ui.shopping.ItemSheetState
import app.lares.client.ui.shopping.ShoppingItemSheet
import app.lares.client.ui.shopping.ShoppingListScreen
import app.lares.client.ui.shopping.ShoppingListUiState
import app.lares.client.ui.shopping.ShoppingListsScreen
import app.lares.client.ui.shopping.ShoppingListsUiState
import app.lares.client.ui.theme.LaresTheme
import app.lares.contract.entities.MemberData
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.ProductCategory
import app.lares.contract.entities.ShoppingItemData
import app.lares.contract.entities.ShoppingItemStatus
import app.lares.contract.entities.ShoppingListData
import app.lares.contract.entities.TagData
import app.lares.contract.permissions.EffectivePermissions
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.Viewer
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalTestApi::class)
class ShoppingUiTest {
    @BeforeTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    private val now = Clock.System.now()
    private val members =
        listOf(
            MemberView("m1", MemberData("p1", "u1", "olga", true), "Olga", PaletteColor.PINK),
            MemberView("m2", MemberData("p2", "u2", "boris", true), "Boris Petrov", PaletteColor.GREEN),
        )

    private fun item(
        id: String,
        name: String,
        category: ProductCategory,
        status: ShoppingItemStatus = ShoppingItemStatus.NEEDED,
        boughtAgo: Int? = null,
        tagIds: Set<String> = emptySet(),
        boughtCount: Int = 0,
    ) = id to
        ShoppingItemData(
            "l1",
            name,
            "m2",
            now - 30.minutes,
            status,
            category,
            boughtBy = boughtAgo?.let { "m2" },
            boughtAt = boughtAgo?.let { now - it.minutes },
            boughtCount = boughtCount,
            tagIds = tagIds,
        )

    private val car = Tag("t1", TagData("Car", PaletteColor.BLUE))

    private fun state(
        items: List<Pair<String, ShoppingItemData>>,
        vararg permissions: Permission = arrayOf(Permission.SHOPPING_VIEW, Permission.SHOPPING_EDIT, Permission.SHOPPING_MANAGE_LISTS),
        expanded: Boolean = false,
        pendingOffline: Boolean = false,
        autocomplete: List<Suggestion> = emptyList(),
    ): ShoppingListUiState {
        val inputs =
            ShoppingInputs(
                listOf("l1" to ShoppingListData("Groceries", "m1")),
                items,
                members,
                listOf(car),
                Viewer("m1", "p1", EffectivePermissions(false, permissions.toSet())),
                now,
            )
        return ShoppingListUiState(
            loaded = true,
            detail = ShoppingViews.detail(inputs, "l1", null),
            banner = if (pendingOffline) uiText(Res.string.shopping_offline_pending) else null,
            boughtExpanded = expanded,
            autocomplete = autocomplete,
        )
    }

    private val twoDepartments =
        listOf(
            item("i1", "Milk", ProductCategory.DAIRY),
            item("i2", "Bread", ProductCategory.BAKERY),
        )

    private val bought =
        listOf(
            item("i1", "Milk", ProductCategory.DAIRY),
            item("b1", "Cheese", ProductCategory.DAIRY, ShoppingItemStatus.BOUGHT, boughtAgo = 1),
            item("b2", "Eggs", ProductCategory.DAIRY, ShoppingItemStatus.BOUGHT, boughtAgo = 2),
            item("b3", "Butter", ProductCategory.DAIRY, ShoppingItemStatus.BOUGHT, boughtAgo = 3),
            item("b4", "Kefir", ProductCategory.DAIRY, ShoppingItemStatus.BOUGHT, boughtAgo = 4),
        )

    @Test
    fun `department headers only with two departments or more`() =
        runComposeUiTest {
            var current by mutableStateOf(state(twoDepartments))
            setContent { LaresTheme { ShoppingListScreen(current) } }
            onNodeWithTag("shopping_department_bakery").assertIsDisplayed()
            onNodeWithTag("shopping_department_dairy").assertIsDisplayed()
            onNodeWithText("Bread and bakery").assertIsDisplayed()

            current = state(listOf(item("i1", "Milk", ProductCategory.DAIRY), item("i3", "Kefir", ProductCategory.DAIRY)))
            waitForIdle()
            onNodeWithTag("shopping_department_dairy").assertDoesNotExist()
            onNodeWithText("Milk").assertIsDisplayed()
            onAllNodesWithText("Boris").assertCountEquals(2)
        }

    @Test
    fun `bought section collapsed and expanded`() =
        runComposeUiTest {
            var current by mutableStateOf(state(bought))
            setContent { LaresTheme { ShoppingListScreen(current) } }
            onNodeWithText("Bought · 4").assertIsDisplayed()
            onNodeWithText("Cheese, Eggs, Butter and 1 more").assertIsDisplayed()
            onNodeWithTag("shopping_item_b1").assertDoesNotExist()

            current = state(bought, expanded = true)
            waitForIdle()
            onNodeWithTag("shopping_item_b1").assertIsDisplayed()
            onNodeWithTag("shopping_clear_bought").assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            onNodeWithText("Kefir").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
            assertEquals(
                TextDecoration.LineThrough,
                layouts
                    .single()
                    .layoutInput.style.textDecoration,
            )
            onNodeWithTag("shopping_item_b1").assertTextContains("Boris · ", substring = true)
        }

    @Test
    fun `offline banner text`() =
        runComposeUiTest {
            setContent { LaresTheme { ShoppingListScreen(state(twoDepartments, pendingOffline = true)) } }
            onNodeWithTag("shopping_banner").assertIsDisplayed()
            onNodeWithText("No network. Your ticks are saved and will be sent when the connection is back.").assertIsDisplayed()
        }

    @Test
    fun `no input and no FAB without the rights`() =
        runComposeUiTest {
            var page by mutableStateOf(true)
            val viewOnly = state(bought, Permission.SHOPPING_VIEW, expanded = true)
            val lists = ShoppingListsUiState(loaded = true, lists = listOf(viewOnly.detail!!.list), canCreate = false)
            setContent { LaresTheme { if (page) ShoppingListScreen(viewOnly) else ShoppingListsScreen(lists) } }
            onNodeWithTag("shopping_add_field").assertDoesNotExist()
            onNodeWithTag("shopping_more").assertDoesNotExist()
            onNodeWithTag("shopping_clear_bought").assertDoesNotExist()
            onNodeWithText("Milk").assertIsDisplayed()

            page = false
            waitForIdle()
            onNodeWithTag("shopping_list_l1").assertIsDisplayed()
            onNodeWithText("4 of 5").assertIsDisplayed()
            onNodeWithTag("shopping_fab").assertDoesNotExist()
        }

    @Test
    fun `the tab with the right to create`() =
        runComposeUiTest {
            var opened: String? = null
            val list = state(twoDepartments).detail!!.list
            setContent {
                LaresTheme { ShoppingListsScreen(ShoppingListsUiState(true, listOf(list), canCreate = true), onOpen = { opened = it }) }
            }
            onNodeWithTag("shopping_fab").assertIsDisplayed()
            onNodeWithText("Left: Bread, Milk").assertIsDisplayed()
            onNodeWithTag("shopping_list_l1").performClick()
            assertEquals("l1", opened)
        }

    @Test
    fun `suggestion chips show under the empty input and hide while typing`() =
        runComposeUiTest {
            val items = twoDepartments + item("c1", "Cheese", ProductCategory.DAIRY, ShoppingItemStatus.CLEARED, boughtAgo = 600)
            setContent { LaresTheme { ShoppingListScreen(state(items)) } }
            onNodeWithTag("shopping_suggestions").assertIsDisplayed()
            onNodeWithText("+ Cheese").assertIsDisplayed()
            onNodeWithTag("shopping_add_field").performTextInput("ch")
            onNodeWithTag("shopping_suggestions").assertDoesNotExist()
        }

    @Test
    fun `the autocomplete shows at most five rows while typing`() =
        runComposeUiTest {
            val names = listOf("Mango", "Melon", "Mint", "Mozzarella", "Muesli", "Mustard", "Mushrooms")
            val entries = names.map { Suggestion(it, ProductCategory.PRODUCE, null, null) }
            setContent { LaresTheme { ShoppingListScreen(state(twoDepartments, autocomplete = entries)) } }
            onNodeWithTag("shopping_autocomplete").assertDoesNotExist()
            onNodeWithTag("shopping_add_field").performTextInput("m")
            onAllNodesWithTag("shopping_autocomplete_row").assertCountEquals(5)
            onAllNodesWithText("Fruit and vegetables").assertCountEquals(5)
        }

    @Test
    fun `the item sheet shows who added and bought it`() =
        runComposeUiTest {
            val items = listOf(item("b1", "Cheese", ProductCategory.DAIRY, ShoppingItemStatus.BOUGHT, boughtAgo = 1, boughtCount = 12))
            val view = state(items).detail!!.bought.single()
            val form = ItemForm.of(view.data)
            setContent { LaresTheme { ShoppingItemSheet(ItemSheetState(view, form, form), listOf(car), canManageTags = false) } }
            onNodeWithTag("shopping_item_sheet").assertIsDisplayed()
            onNodeWithText("Added: Boris · ", substring = true).assertIsDisplayed()
            onNodeWithText("Bought: Boris · ", substring = true).assertIsDisplayed()
            onNodeWithText("Bought 12 times").assertIsDisplayed()
            onNodeWithText("Dairy and eggs").assertIsDisplayed()
            onNodeWithTag("shopping_item_save").assertIsNotEnabled()
        }

    @Test
    fun `the tag filter row only when a tag is in use`() =
        runComposeUiTest {
            var current by mutableStateOf(state(twoDepartments))
            setContent { LaresTheme { ShoppingListScreen(current) } }
            onNodeWithTag("shopping_tag_filter").assertDoesNotExist()

            current =
                state(listOf(item("i1", "Milk", ProductCategory.DAIRY, tagIds = setOf("t1")), item("i2", "Bread", ProductCategory.BAKERY)))
            waitForIdle()
            onNodeWithTag("shopping_tag_filter").assertIsDisplayed()
            onNodeWithTag("shopping_filter_all").assertIsDisplayed()
            onNodeWithTag("shopping_filter_t1").assertIsDisplayed()
            onAllNodesWithText("Car").assertCountEquals(2)
        }
}
