package app.lares.client.ui

import app.lares.client.data.HouseholdRepository
import app.lares.client.data.calendar.CalendarItem
import app.lares.client.data.calendar.CalendarRepository
import app.lares.client.data.calendar.CalendarViews
import app.lares.client.data.location.DIMA
import app.lares.client.data.location.EnglishLocationTexts
import app.lares.client.data.location.LocationFixture
import app.lares.client.data.location.LocationTexts
import app.lares.client.data.location.MemberOnMap
import app.lares.client.data.shopping.ShoppingRepository
import app.lares.client.data.shopping.ShoppingViews
import app.lares.client.data.tasks.TaskInputs
import app.lares.client.data.tasks.TasksRepository
import app.lares.client.ui.FamilyFixture.change
import app.lares.client.ui.location.LiveLocations
import app.lares.client.ui.location.LiveLocationsState
import app.lares.client.ui.location.LocatedMember
import app.lares.client.ui.main.TodayViewModel
import app.lares.client.ui.tasks.TaskEvent
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.ProductCategory
import app.lares.contract.entities.ShoppingItemData
import app.lares.contract.entities.ShoppingListData
import app.lares.contract.entities.TaskData
import app.lares.contract.permissions.EffectivePermissions
import app.lares.contract.permissions.Permission
import app.lares.contract.sync.Change
import app.lares.contract.sync.EntityTypes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** The Today blocks: the active shopping list (SHP-013) and the tasks (TSK-023, FND-063). */
class TodayViewModelTest {
    private val today = TaskUiFixture.today

    private fun task(
        id: String,
        data: TaskData,
    ) = change(EntityTypes.TASK, id, TaskData.serializer(), data)

    private fun core(vararg extra: Pair<String, TaskData>) =
        FamilyFixture.core(FamilyFixture.olga, TaskUiFixture.changes + extra.map { (id, data) -> task(id, data) })

    @Test
    fun `my overdue tasks come first, then today, then done today`() {
        core(
            "t-mine-over" to TaskData("Renew insurance", "m2", dueDate = today.minus(2, DateTimeUnit.DAY), assigneeIds = setOf("m1")),
            "t-any-today" to TaskData("Water the plants", "m2", dueDate = today),
        ).use { core ->
            val vm = core.koin.get<TodayViewModel>()
            val state = awaitState(vm.state) { it.rows.size == 3 }
            assertTrue(state.showTasks)
            assertTrue(state.canCreate)
            // Boris's overdue task, Carol's routine and undated tasks are not in my day.
            assertEquals("t-mine-over", state.rows.first().key)
            assertEquals(
                setOf("t-today", "t-any-today"),
                state.rows
                    .drop(1)
                    .map { it.key }
                    .toSet(),
            )
            assertEquals(3, state.count)
            assertEquals(0, state.more)

            vm.toggle(state.rows.single { it.key == "t-today" })
            assertIs<TaskEvent.Completed>(TaskUiFixture.next(vm.events))
            val done = awaitState(vm.state) { it.count == 2 }
            assertEquals(listOf("t-mine-over", "t-any-today", "t-today"), done.rows.map { it.key }, "the done row goes last")
            assertTrue(done.rows.last().done)
        }
    }

    @Test
    fun `at most six rows and the rest as more`() {
        val extra = (1..7).map { "t-day-$it" to TaskData("Chore $it", "m1", dueDate = today, assigneeIds = setOf("m1")) }
        core(*extra.toTypedArray()).use { core ->
            val vm = core.koin.get<TodayViewModel>()
            val state = awaitState(vm.state) { it.count == 8 }
            assertEquals(TodayViewModel.MAX_ROWS, state.rows.size)
            assertEquals(2, state.more)
        }
    }

    @Test
    fun `hidden without tasks view`() {
        TaskUiFixture.core().use { core ->
            val inputs =
                runBlocking {
                    core.koin
                        .get<TasksRepository>()
                        .observeInputs()
                        .first { it.viewer != null }
                }
            val noTasks =
                inputs.copy(
                    viewer = inputs.viewer!!.copy(permissions = EffectivePermissions(false, setOf(Permission.SHOPPING_VIEW))),
                )
            val state = TodayViewModel.build(noTasks)
            assertTrue(state.loaded)
            assertFalse(state.showTasks)
            assertFalse(state.canCreate)
            assertTrue(state.rows.isEmpty())
        }
    }

    private fun shoppingCore(vararg extra: Change) = FamilyFixture.core(FamilyFixture.olga, ShoppingUiFixture.changes + extra)

    private fun UiTestCore.taskInputs(): TaskInputs =
        runBlocking { koin.get<TasksRepository>().observeInputs().first { it.viewer != null } }

    private fun UiTestCore.shoppingInputs() =
        runBlocking { koin.get<ShoppingRepository>().observeInputs().first { it.viewer != null && it.lists.isNotEmpty() } }

    @Test
    fun `no shopping card without lists to buy`() {
        core().use { core ->
            val state = awaitState(core.koin.get<TodayViewModel>().state) { it.loaded && it.showTasks }
            assertNull(state.shopping)
            assertFalse(state.empty)
        }
    }

    @Test
    fun `the card shows the list with items to buy and who is shopping`() {
        shoppingCore().use { core ->
            val card = assertNotNull(awaitState(core.koin.get<TodayViewModel>().state) { it.shopping != null }.shopping)
            assertEquals("l1", card.listId)
            assertEquals("Groceries", card.name)
            assertEquals(1, card.bought)
            assertEquals(4, card.total)
            assertEquals(listOf("Apples", "Bread", "Milk"), card.remaining)
            assertEquals(0, card.more)
            assertEquals("m2", card.shopper?.id, "Boris ticked Eggs 5 minutes ago")
            assertEquals(0, card.othersToBuy, "Pharmacy has nothing to buy")
        }
    }

    @Test
    fun `the most recently changed list wins and the others are counted`() {
        val now = Clock.System.now()
        shoppingCore(
            change(EntityTypes.SHOPPING_LIST, "l3", ShoppingListData.serializer(), ShoppingListData("Hardware", "m1")),
            change(
                EntityTypes.SHOPPING_ITEM,
                "i-nails",
                ShoppingItemData.serializer(),
                ShoppingItemData("l3", "Nails", "m1", now - 1.minutes, category = ProductCategory.HOUSEHOLD),
            ),
        ).use { core ->
            val card = assertNotNull(awaitState(core.koin.get<TodayViewModel>().state) { it.shopping?.listId == "l3" }.shopping)
            assertEquals(listOf("Nails"), card.remaining)
            assertNull(card.shopper)
            assertEquals(1, card.othersToBuy, "Groceries still has items to buy")
        }
    }

    @Test
    fun `who is shopping expires after 15 minutes`() {
        shoppingCore().use { core ->
            val tasks = core.taskInputs()
            val shopping = core.shoppingInputs()
            assertEquals(
                "m2",
                TodayViewModel
                    .build(tasks, ShoppingViews.active(shopping))
                    .shopping
                    ?.shopper
                    ?.id,
            )
            val later = TodayViewModel.build(tasks, ShoppingViews.active(shopping.copy(now = shopping.now + 11.minutes)))
            assertNotNull(later.shopping, "the list still has items to buy")
            assertNull(later.shopping.shopper, "Eggs were ticked 16 minutes ago")
        }
    }

    @Test
    fun `no card without shopping view, empty state only when nothing is shown`() {
        shoppingCore().use { core ->
            val tasks = core.taskInputs()
            val active = ShoppingViews.active(core.shoppingInputs())
            val viewer = tasks.viewer!!

            fun with(vararg permissions: Permission) =
                TodayViewModel.build(
                    tasks.copy(viewer = viewer.copy(permissions = EffectivePermissions(false, permissions.toSet()))),
                    active,
                )

            val tasksOnly = with(Permission.TASKS_VIEW)
            assertNull(tasksOnly.shopping)
            assertFalse(tasksOnly.empty)

            val shoppingOnly = with(Permission.SHOPPING_VIEW)
            assertNotNull(shoppingOnly.shopping)
            assertFalse(shoppingOnly.showTasks)
            assertFalse(shoppingOnly.empty, "the card alone is enough")

            assertTrue(with(Permission.CALENDAR_VIEW).empty)
        }
    }

    @Test
    fun `upcoming shows events, birthdays and memorable dates of the next 7 days (CAL-019)`() {
        CalendarUiFixture.core().use { core ->
            val state = awaitState(core.koin.get<TodayViewModel>().state) { it.upcoming != null }
            val upcoming = assertNotNull(state.upcoming)
            assertEquals(CalendarUiFixture.today, upcoming.today)
            assertEquals(CalendarViews.UPCOMING_MAX, upcoming.items.size)
            val first = assertIs<CalendarItem.Birthday>(upcoming.items.first().item, "Grandma's birthday is today")
            assertEquals("p4", first.person.id)
            val events = upcoming.items.mapNotNull { (it.item as? CalendarItem.Event)?.occ?.eventId }
            assertTrue(
                CalendarUiFixture.E_ANNIV in events && CalendarUiFixture.E_DENTIST in events && CalendarUiFixture.E_HOLIDAY in events,
            )
            // Today's dance (17:30–18:45 Moscow) drops out once it is over, so the row count depends on the time of day:
            // the moved dance in a week is either the last row shown or counted in «Ещё N».
            val danceInWeek =
                upcoming.items.any {
                    it.date == CalendarUiFixture.week &&
                        (it.item as? CalendarItem.Event)?.occ?.eventId == CalendarUiFixture.E_DANCE
                }
            assertTrue(danceInWeek || upcoming.more >= 1, "the moved dance in a week is shown or counted")
            assertTrue("Carol" in upcoming.allNames)
        }
    }

    @Test
    fun `upcoming hidden without calendar view`() {
        CalendarUiFixture.core().use { core ->
            val tasks = core.taskInputs()
            val coming =
                runBlocking {
                    core.koin
                        .get<CalendarRepository>()
                        .observeUpcoming()
                        .first { it.items.isNotEmpty() }
                }
            val viewer = tasks.viewer!!

            fun with(vararg permissions: Permission) =
                TodayViewModel.build(
                    tasks.copy(viewer = viewer.copy(permissions = EffectivePermissions(false, permissions.toSet()))),
                    null,
                    coming,
                )

            assertNull(with(Permission.TASKS_VIEW).upcoming)
            val calendarOnly = with(Permission.CALENDAR_VIEW)
            assertNotNull(calendarOnly.upcoming)
            assertFalse(calendarOnly.empty, "«Скоро» alone is enough")
            assertNull(TodayViewModel.build(tasks, null, coming.copy(items = emptyList())).upcoming, "nothing coming: no block")
        }
    }

    private fun located(
        id: String,
        name: String,
        status: SharingStatus = SharingStatus.SHARING,
    ) = LocatedMember(
        MemberOnMap(id, null, name, PaletteColor.GREEN, VisibilityLevel.CURRENT, status, null, null, null, null, null, false, false),
        "$name · now",
    )

    @Test
    fun `LOC-016 family now - the others who share or pause, by name, at most four`() {
        val state =
            LiveLocationsState(
                me = "me",
                members =
                    listOf(
                        located("me", "Ольга"),
                        located("m5", "Тимур"),
                        located("m2", "Аня"),
                        located("m3", "Дима", SharingStatus.PAUSED),
                        located("m4", "Бабушка", SharingStatus.NEVER),
                        located("m6", "Вера"),
                        located("m7", "Глеб", SharingStatus.OFF_ON_PHONE),
                        located("m8", "Яна"),
                    ),
            )
        val block = assertNotNull(TodayViewModel.familyNow(state))
        assertEquals(listOf("Аня", "Вера", "Дима", "Тимур"), block.rows.map { it.member.name })
        assertEquals("Аня · now", block.rows.first().status)
        assertNull(TodayViewModel.familyNow(state.copy(members = listOf(located("me", "Ольга")))), "nobody else: no block")
        assertNull(TodayViewModel.familyNow(LiveLocationsState(me = "me")), "no location.view: no members, no block")
    }

    @Test
    fun `LOC-016 without location view nothing is asked from the server`() {
        val f = LocationFixture()
        try {
            runBlocking {
                f.family()
                f.loginAs(DIMA, Permission.TASKS_VIEW)
                val live =
                    LiveLocations(
                        f.repository(),
                        LocationTexts(EnglishLocationTexts()),
                        HouseholdRepository(f.localDb),
                        f.localDb,
                        f.permissions,
                        f.placesRepository,
                        f.placesWriter,
                        f.clock,
                    )
                val state = live.observe().first()
                assertTrue(state.members.isEmpty())
                assertNull(TodayViewModel.familyNow(state))
                assertEquals(0, f.liveConnects)
                assertTrue(f.requests.none { it.url.encodedPath.startsWith("/api/v1/location") })
            }
        } finally {
            f.close()
        }
    }
}
