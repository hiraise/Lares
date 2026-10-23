package app.familyhub.client.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runComposeUiTest
import app.familyhub.client.ui.family.FamilyAccess
import app.familyhub.client.ui.family.FamilyNavigation
import app.familyhub.client.ui.family.FamilyScreen
import app.familyhub.client.ui.family.FamilyTab
import app.familyhub.client.ui.family.FamilyUiState
import app.familyhub.client.ui.family.GroupEditorActions
import app.familyhub.client.ui.family.GroupEditorScreen
import app.familyhub.client.ui.family.GroupEditorUiState
import app.familyhub.client.ui.family.GroupItem
import app.familyhub.client.ui.family.MemberItem
import app.familyhub.client.ui.family.PermissionChecklist
import app.familyhub.client.ui.theme.FamilyHubTheme
import app.familyhub.contract.entities.PaletteColor
import app.familyhub.contract.permissions.BuiltInGroup
import app.familyhub.contract.permissions.Permission
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FamilyUiTest {
    @BeforeTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    private val admins = GroupItem("g1", "Administrators", BuiltInGroup.ADMINISTRATORS, emptySet(), setOf("m1"))
    private val kids = GroupItem("g2", "Kids", null, setOf(Permission.TASKS_VIEW), setOf("m2"))
    private val members =
        listOf(
            MemberItem("m1", "p1", "Olga", PaletteColor.PINK, "olga", true, listOf(admins), isMe = true),
            MemberItem("m2", "p2", "Anya", PaletteColor.PURPLE, null, false, listOf(kids), isMe = false),
        )

    private val navigation = FamilyNavigation({}, {}, {}, {}, {}, {})

    @Test
    fun `members list shows the admin badge, groups and the invite action for admins`() =
        runComposeUiTest {
            var access by mutableStateOf(FamilyAccess(manageFamily = true, managePersons = true, manageTags = true))
            setContent {
                FamilyHubTheme(darkTheme = false) {
                    FamilyScreen(
                        FamilyUiState(
                            loaded = true,
                            access = access,
                            members = members,
                            groups = listOf(admins, kids),
                        ),
                        FamilyTab.MEMBERS,
                        {
                        },
                        navigation,
                    )
                }
            }
            onNodeWithText("Olga").assertIsDisplayed()
            onNodeWithText("(you)").assertIsDisplayed()
            assertEquals(1, onAllNodesWithTag("admin_chip", useUnmergedTree = true).fetchSemanticsNodes().size)
            onNodeWithText("Kids").assertIsDisplayed()
            onNodeWithText("Invite").assertIsDisplayed()
            access = FamilyAccess()
            waitForIdle()
            assertEquals(0, onAllNodesWithTag("family_fab").fetchSemanticsNodes().size, "no invite action without household.manage")
        }

    @Test
    fun `group editor checklist checks See with an action and clears actions with See`() =
        runComposeUiTest {
            var state by mutableStateOf(
                GroupEditorUiState(
                    loaded = true,
                    isNew = false,
                    name = "Kids",
                    permissions = emptySet(),
                    members = members,
                    canEdit = true,
                ),
            )
            val actions =
                GroupEditorActions(
                    onPermission = { permission, checked ->
                        state = state.copy(permissions = PermissionChecklist.toggle(state.permissions, permission, checked))
                    },
                    onMember = {
                        id,
                        checked,
                        ->
                        state = state.copy(memberIds = if (checked) state.memberIds + id else state.memberIds - id)
                    },
                )
            setContent { FamilyHubTheme(darkTheme = false) { GroupEditorScreen(state, actions, onBack = {}) } }
            onNodeWithText("What they can do").assertIsDisplayed()
            scrollTo("perm_calendar.edit_any")
            onNodeWithTag("perm_calendar.edit_any").performClick()
            onNodeWithTag("perm_calendar.view").assertIsOn()
            onNodeWithTag("perm_calendar.edit_any").assertIsOn()
            onNodeWithTag("perm_calendar.view").performClick()
            onNodeWithTag("perm_calendar.view").assertIsOff()
            onNodeWithTag("perm_calendar.edit_any").assertIsOff()
            assertEquals(emptySet(), state.permissions)
            scrollTo("group_member_m2")
            onNodeWithTag("group_member_m2").performClick()
            assertEquals(setOf("m2"), state.memberIds)
        }

    @Test
    fun `administrators group shows full access and no permission checklist`() =
        runComposeUiTest {
            setContent {
                FamilyHubTheme(darkTheme = true) {
                    GroupEditorScreen(
                        GroupEditorUiState(
                            loaded = true,
                            isNew = false,
                            builtIn = BuiltInGroup.ADMINISTRATORS,
                            memberIds = setOf("m1"),
                            members = members,
                            canEdit = false,
                        ),
                        GroupEditorActions(),
                        onBack = {},
                    )
                }
            }
            onNodeWithText("Full access to everything, including all data").assertIsDisplayed()
            assertEquals(0, onAllNodesWithTag("perm_tasks.view").fetchSemanticsNodes().size)
            scrollTo("group_member_m1")
            onNodeWithTag("group_member_m1").assertIsOn().assertIsNotEnabled()
        }

    private fun androidx.compose.ui.test.ComposeUiTest.scrollTo(tag: String) {
        onNode(
            androidx.compose.ui.test
                .hasScrollAction(),
        ).performScrollToNode(hasTestTag(tag))
    }
}
