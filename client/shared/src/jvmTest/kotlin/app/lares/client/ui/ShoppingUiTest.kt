package app.lares.client.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import app.lares.client.data.MemberView
import app.lares.client.data.shopping.ShoppingInputs
import app.lares.client.data.shopping.ShoppingViews
import app.lares.client.resources.Res
import app.lares.client.resources.shopping_offline_pending
import app.lares.client.ui.common.uiText
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
        )

    private fun state(
        items: List<Pair<String, ShoppingItemData>>,
        vararg permissions: Permission = arrayOf(Permission.SHOPPING_VIEW, Permission.SHOPPING_EDIT, Permission.SHOPPING_MANAGE_LISTS),
        expanded: Boolean = false,
        pendingOffline: Boolean = false,
    ): ShoppingListUiState {
        val inputs =
            ShoppingInputs(
                listOf("l1" to ShoppingListData("Groceries", "m1")),
                items,
                members,
                emptyList(),
                Viewer("m1", "p1", EffectivePermissions(false, permissions.toSet())),
                now,
            )
        return ShoppingListUiState(
            loaded = true,
            detail = ShoppingViews.detail(inputs, "l1", null),
            banner = if (pendingOffline) uiText(Res.string.shopping_offline_pending) else null,
            boughtExpanded = expanded,
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
}
