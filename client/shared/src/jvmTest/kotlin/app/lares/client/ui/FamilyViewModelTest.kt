package app.lares.client.ui

import app.lares.client.data.Tag
import app.lares.client.resources.Res
import app.lares.client.resources.error_tag_name_taken
import app.lares.client.resources.family_birthday_incomplete
import app.lares.client.ui.common.FieldError
import app.lares.client.ui.common.UiError
import app.lares.client.ui.family.AddMemberMode
import app.lares.client.ui.family.AddMemberViewModel
import app.lares.client.ui.family.FamilyAccess
import app.lares.client.ui.family.FamilyViewModel
import app.lares.client.ui.family.GroupEditorViewModel
import app.lares.client.ui.family.MemberDetailViewModel
import app.lares.client.ui.family.PermissionChecklist
import app.lares.client.ui.family.PersonForm
import app.lares.client.ui.family.PersonViewModel
import app.lares.client.ui.family.TagRules
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.api.admin.CreateInviteRequest
import app.lares.contract.api.admin.InviteCreated
import app.lares.contract.api.admin.InviteInfo
import app.lares.contract.api.admin.UpdateGroupRequest
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.TagData
import app.lares.contract.permissions.EffectivePermissions
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.PermissionCatalogue
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class FamilyViewModelTest {
    @Test
    fun `edit controls follow permissions - admin, Adults, Kids`() {
        val admin = FamilyAccess.of(EffectivePermissions(isAdmin = true, permissions = emptySet()))
        assertEquals(FamilyAccess(manageFamily = true, managePersons = true, manageTags = true), admin)
        val adults = FamilyAccess.of(EffectivePermissions(false, PermissionCatalogue.withImplied(PermissionCatalogue.adultsTemplate)))
        assertEquals(FamilyAccess(manageFamily = false, managePersons = true, manageTags = true), adults)
        val kids = FamilyAccess.of(EffectivePermissions(false, PermissionCatalogue.withImplied(PermissionCatalogue.kidsTemplate)))
        assertEquals(FamilyAccess(), kids)
    }

    @Test
    fun `family state follows the synced groups for admin and kid`() {
        FamilyFixture.core(FamilyFixture.olga).use { core ->
            val vm = core.koin.get<FamilyViewModel>()
            val state = awaitState(vm.state) { it.members.size == 3 && it.access.manageFamily }
            assertEquals(listOf("m1", "m2", "m3"), state.members.map { it.id }, "own member first, then by name")
            assertTrue(state.members.first().isMe && state.members.first().isAdmin)
            assertEquals(listOf(FamilyFixture.ADMINS, FamilyFixture.ADULTS, FamilyFixture.KIDS), state.groups.map { it.id })
            assertEquals(listOf("Boris", "Carol", "Grandma", "Olga"), state.persons.map { it.person.data.name })
            assertEquals(listOf(false), state.persons.filter { it.person.id == "p4" }.map { it.isMember })
            assertEquals(listOf("Car"), state.tags.map { it.data.name })
        }
        FamilyFixture.core(FamilyFixture.carol).use { core ->
            val vm = core.koin.get<FamilyViewModel>()
            val state = awaitState(vm.state) { it.members.size == 3 }
            assertEquals(FamilyAccess(), state.access)
            assertNull(state.invites, "invites are not loaded without household.manage")
            assertFalse(core.paths().contains("/api/v1/invites"))
        }
    }

    @Test
    fun `invites are loaded for admins and revoked`() {
        val invite = InviteInfo("i1", setOf(FamilyFixture.ADULTS), "m1", Clock.System.now(), Clock.System.now() + 72.hours)
        var revoked = false
        FamilyFixture
            .core(FamilyFixture.olga) { request ->
                when {
                    request.url.encodedPath == "/api/v1/invites" && request.method == HttpMethod.Get -> {
                        json(ListSerializer(InviteInfo.serializer()), if (revoked) emptyList() else listOf(invite))
                    }

                    request.url.encodedPath == "/api/v1/invites/i1" && request.method == HttpMethod.Delete -> {
                        revoked = true
                        respond("", HttpStatusCode.NoContent)
                    }

                    else -> {
                        null
                    }
                }
            }.use { core ->
                val vm = core.koin.get<FamilyViewModel>()
                val state = awaitState(vm.state) { it.invites?.size == 1 }
                assertEquals(
                    listOf(FamilyFixture.ADULTS),
                    state.invites!!
                        .single()
                        .groups
                        .map { it.id },
                )
                vm.revokeInvite("i1")
                awaitState(vm.state) { it.invites?.isEmpty() == true }
            }
    }

    @Test
    fun `permission checklist mirrors implied section access`() {
        var perms = PermissionChecklist.toggle(emptySet(), Permission.TASKS_EDIT_ANY, true)
        assertEquals(setOf(Permission.TASKS_EDIT_ANY, Permission.TASKS_VIEW), perms)
        perms = PermissionChecklist.toggle(perms, Permission.BUDGET_MANAGE, true)
        perms = PermissionChecklist.toggle(perms, Permission.TASKS_VIEW, false)
        assertEquals(setOf(Permission.BUDGET_MANAGE, Permission.BUDGET_VIEW), perms, "unchecking See … unchecks its actions")
        perms = PermissionChecklist.toggle(perms, Permission.BUDGET_MANAGE, false)
        assertEquals(setOf(Permission.BUDGET_VIEW), perms, "unchecking an action keeps See …")
        perms = PermissionChecklist.toggle(perms, Permission.HOUSEHOLD_MANAGE, true)
        assertEquals(setOf(Permission.BUDGET_VIEW, Permission.HOUSEHOLD_MANAGE), perms)
        assertEquals(Permission.entries.toSet(), PermissionChecklist.sections.flatMap { it.second }.toSet(), "every permission is editable")
    }

    @Test
    fun `group editor - checklist, last admin and the update request`() {
        var body: String? = null
        FamilyFixture
            .core(FamilyFixture.olga) { request ->
                if (request.url.encodedPath.startsWith("/api/v1/groups/") && request.method == HttpMethod.Patch) {
                    body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond("", HttpStatusCode.NoContent)
                } else {
                    null
                }
            }.use { core ->
                val kids =
                    GroupEditorViewModel(
                        core.session,
                        core.koin.get(),
                        core.koin.get(),
                        core.koin.get(),
                        core.koin.get(),
                        core.koin.get(),
                        FamilyFixture.KIDS,
                    )
                awaitState(kids.state) { it.loaded && it.canEdit && it.members.size == 3 }
                kids.togglePermission(Permission.CALENDAR_EDIT_ANY, true)
                kids.toggleMember("m2", true)
                kids.save()
                awaitState(kids.state) { it.done }
                val request = LaresJson.decodeFromString(UpdateGroupRequest.serializer(), body!!)
                assertEquals(setOf("m2", "m3"), request.memberIds)
                assertTrue(Permission.CALENDAR_EDIT_ANY in request.permissions!! && Permission.CALENDAR_VIEW in request.permissions!!)

                val admins =
                    GroupEditorViewModel(
                        core.session,
                        core.koin.get(),
                        core.koin.get(),
                        core.koin.get(),
                        core.koin.get(),
                        core.koin.get(),
                        FamilyFixture.ADMINS,
                    )
                awaitState(admins.state) { it.loaded && it.isAdministrators }
                admins.togglePermission(Permission.BUDGET_VIEW, false)
                admins.toggleMember("m1", false)
                val state = awaitState(admins.state) { it.action.error != null }
                assertEquals(UiError.Api(ErrorCode.LAST_ADMIN), state.action.error)
                assertEquals(setOf("m1"), state.memberIds)
                assertEquals(emptySet(), state.permissions, "administrators' permissions are not editable")
            }
    }

    @Test
    fun `invite creation sends groups, expiry and the relative, then shows the QR`() {
        var request: CreateInviteRequest? = null
        val created = InviteCreated("i9", "ABCD2345", "lares://invite?server=x&code=ABCD2345", Clock.System.now() + 24.hours)
        FamilyFixture
            .core(FamilyFixture.olga) { req ->
                if (req.url.encodedPath == "/api/v1/invites" && req.method == HttpMethod.Post) {
                    request =
                        LaresJson.decodeFromString(
                            CreateInviteRequest.serializer(),
                            (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
                        )
                    json(InviteCreated.serializer(), created, HttpStatusCode.Created)
                } else {
                    null
                }
            }.use { core ->
                val vm =
                    AddMemberViewModel(
                        core.koin.get(),
                        core.koin.get(),
                        core.koin.get(),
                        core.koin.get(),
                        core.koin.get(),
                        AddMemberMode.INVITE,
                    )
                val ready = awaitState(vm.state) { it.groups.size == 3 && it.relatives.isNotEmpty() }
                assertEquals(listOf("p4"), ready.relatives.map { it.id }, "only relatives without an account")
                vm.toggleGroup(FamilyFixture.ADULTS, true)
                vm.setExpiryDays(1)
                vm.setRelative("p4")
                vm.submit()
                val state = awaitState(vm.state) { it.invite != null }
                assertEquals(created, state.invite)
                assertEquals(CreateInviteRequest(setOf(FamilyFixture.ADULTS), 24, "p4"), request)
            }
    }

    @Test
    fun `member detail validates the new password and maps LAST_ADMIN`() {
        FamilyFixture
            .core(FamilyFixture.olga) { req ->
                if (req.url.encodedPath == "/api/v1/members/m1/groups") apiError(HttpStatusCode.Conflict, ErrorCode.LAST_ADMIN) else null
            }.use { core ->
                val vm =
                    MemberDetailViewModel(
                        core.session,
                        core.koin.get(),
                        core.koin.get(),
                        core.koin.get(),
                        core.koin.get(),
                        core.koin.get(),
                        "m1",
                    )
                awaitState(vm.state) { it.member != null && it.access.manageFamily }
                vm.resetPassword("short", "other")
                assertEquals(
                    setOf(MemberDetailViewModel.NEW_PASSWORD, app.lares.client.ui.onboarding.SetupFields.PASSWORD_REPEAT),
                    awaitState(vm.state) { it.passwordFields.isNotEmpty() }.passwordFields.keys,
                )
                vm.setGroups(setOf(FamilyFixture.ADULTS))
                assertEquals(UiError.Api(ErrorCode.LAST_ADMIN), awaitState(vm.state) { it.action.error != null }.action.error)
            }
    }

    @Test
    fun `relative form - day and month together, year optional, Feb 29 only in leap years`() {
        assertEquals(emptyMap(), PersonForm(name = "Grandma").validate(2026))
        assertEquals(emptyMap(), PersonForm(name = "Grandma", birthDay = 29, birthMonth = 2).validate(2026), "Feb 29 without a year")
        assertEquals(emptyMap(), PersonForm(name = "Grandma", birthDay = 29, birthMonth = 2, birthYear = 1948).validate(2026))
        assertEquals(
            setOf(PersonForm.BIRTHDAY),
            PersonForm(name = "Grandma", birthDay = 29, birthMonth = 2, birthYear = 1949).validate(2026).keys,
        )
        assertEquals(
            FieldError(Res.string.family_birthday_incomplete),
            PersonForm(name = "Grandma", birthMonth = 3).validate(2026)[PersonForm.BIRTHDAY],
        )
        assertEquals(setOf(PersonForm.BIRTHDAY), PersonForm(name = "X", birthDay = 1, birthMonth = 1, birthYear = 2030).validate(2026).keys)
        assertEquals(FieldError.of(FieldErrorCode.REQUIRED), PersonForm(name = "  ").validate(2026)["name"])
        val blankContact =
            PersonForm(
                name = "X",
                contacts =
                    listOf(
                        app.lares.contract.entities
                            .ContactData(app.lares.contract.entities.ContactKind.PHONE, " "),
                    ),
            )
        assertEquals(emptyList(), blankContact.toData().contacts, "empty contacts are dropped")
    }

    @Test
    fun `relative form saves through sync and members' profiles cannot be deleted`() {
        FamilyFixture.core(FamilyFixture.olga).use { core ->
            val grandma =
                PersonViewModel(core.session, core.koin.get(), core.koin.get(), core.koin.get(), core.koin.get(), Clock.System, "p4")
            val loaded = awaitState(grandma.state) { it.loaded && it.canEdit }
            assertTrue(loaded.canDelete)
            assertEquals(PaletteColor.ORANGE, loaded.color)
            grandma.onBirthday(29, 2, null)
            grandma.onColor(PaletteColor.TEAL)
            grandma.save()
            awaitState(grandma.state) { it.done }
            val saved =
                awaitState(
                    core.koin.get<app.lares.client.data.PersonsRepository>().observe("p4"),
                ) { it?.data?.birthDay == 29 }!!
            assertEquals(PaletteColor.TEAL, saved.data.color)

            val boris =
                PersonViewModel(core.session, core.koin.get(), core.koin.get(), core.koin.get(), core.koin.get(), Clock.System, "p2")
            val member = awaitState(boris.state) { it.loaded && it.isMember }
            assertFalse(member.canDelete)
        }
        FamilyFixture.core(FamilyFixture.carol).use { core ->
            val grandma =
                PersonViewModel(core.session, core.koin.get(), core.koin.get(), core.koin.get(), core.koin.get(), Clock.System, "p4")
            assertFalse(awaitState(grandma.state) { it.loaded }.canEdit, "kids do not manage relatives")
            val own = PersonViewModel(core.session, core.koin.get(), core.koin.get(), core.koin.get(), core.koin.get(), Clock.System, "p3")
            assertTrue(awaitState(own.state) { it.loaded && it.isMe }.canEdit, "everyone edits their own profile")
        }
    }

    @Test
    fun `tag names are checked against local tags, archived included`() {
        val tags = listOf(Tag("t1", TagData("Car", PaletteColor.BLUE)), Tag("t2", TagData("Dacha", PaletteColor.GREEN, archived = true)))
        assertEquals(FieldError(Res.string.error_tag_name_taken), TagRules.nameError(" car ", tags, null))
        assertEquals(FieldError(Res.string.error_tag_name_taken), TagRules.nameError("DACHA", tags, null))
        assertNull(TagRules.nameError("Car", tags, "t1"), "renaming a tag to its own name")
        assertEquals(FieldError.of(FieldErrorCode.REQUIRED), TagRules.nameError(" ", tags, null))

        FamilyFixture.core(FamilyFixture.olga).use { core ->
            val vm = core.koin.get<FamilyViewModel>()
            awaitState(vm.state) { it.tags.size == 1 }
            vm.newTag()
            vm.onTagName("CAR")
            vm.saveTag()
            assertNotNull(awaitState(vm.tagSheet) { it?.nameError != null }!!.nameError)
            vm.onTagName("School")
            vm.onTagColor(PaletteColor.RED)
            vm.saveTag()
            awaitState(vm.tagSheet) { it == null }
            val state = awaitState(vm.state) { it.tags.size == 2 }
            assertEquals(
                PaletteColor.RED,
                state.tags
                    .single { it.data.name == "School" }
                    .data.color,
            )
        }
    }
}
