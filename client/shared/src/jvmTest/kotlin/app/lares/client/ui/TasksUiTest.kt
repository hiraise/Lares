package app.lares.client.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import app.lares.client.data.MemberView
import app.lares.client.data.Tag
import app.lares.client.data.tasks.TaskCompletion
import app.lares.client.data.tasks.TaskDetail
import app.lares.client.data.tasks.TaskFilter
import app.lares.client.data.tasks.TaskScope
import app.lares.client.data.tasks.TaskView
import app.lares.client.ui.tasks.TaskDetailScreen
import app.lares.client.ui.tasks.TaskDetailUiState
import app.lares.client.ui.tasks.TaskRowUi
import app.lares.client.ui.tasks.TaskSectionUi
import app.lares.client.ui.tasks.TasksLogic
import app.lares.client.ui.tasks.TasksScreen
import app.lares.client.ui.tasks.TasksUiState
import app.lares.client.ui.theme.LaresTheme
import app.lares.contract.entities.MemberData
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.entities.TagData
import app.lares.contract.entities.TaskCompletionData
import app.lares.contract.entities.TaskData
import app.lares.contract.tasks.TaskGroup
import app.lares.contract.tasks.TaskState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class TasksUiTest {
    @BeforeTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    private val today = LocalDate(2026, 10, 21)
    private val zone = TimeZone.of("Europe/Moscow")
    private val boris = MemberView("m2", MemberData("p2", "u2", "boris", true), "Boris", PaletteColor.GREEN)
    private val names = mapOf("m1" to "Olga", "m2" to "Boris")
    private val car = Tag("t1", TagData("Car", PaletteColor.BLUE))

    private fun view(
        id: String,
        data: TaskData,
        group: TaskGroup,
        overdue: Boolean = false,
        completedToday: TaskCompletion? = null,
    ) = TaskView(
        id = id,
        data = data,
        state = TaskState(data.dueDate, done = false, doneBy = null),
        group = group,
        overdue = overdue,
        dueMoment = null,
        assignees = if ("m2" in data.assigneeIds) listOf(boris) else emptyList(),
        tags = if ("t1" in data.tagIds) listOf(car) else emptyList(),
        checklistDone = 1,
        checklistTotal = if (id == "t-today") 3 else 0,
        canEdit = true,
        canComplete = true,
        completedToday = completedToday,
    )

    private fun row(
        view: TaskView,
        done: Boolean = false,
        key: String = view.id,
    ) = TaskRowUi(key, view, TasksLogic.meta(view, today, zone, names), done, canToggle = true)

    @Test
    fun `sections, overdue meta and done rows`() =
        runComposeUiTest {
            val over =
                view(
                    "t-over",
                    TaskData(
                        "Pay internet",
                        "m1",
                        dueDate = LocalDate(2026, 10, 20),
                        dueTime = LocalTime(21, 0),
                        assigneeIds = setOf("m2"),
                    ),
                    TaskGroup.OVERDUE,
                    overdue = true,
                )
            val open = view("t-today", TaskData("Pick up Anya", "m1", dueDate = today, tagIds = setOf("t1")), TaskGroup.TODAY)
            val completion = TaskCompletion("c1", TaskCompletionData("t-done", "m2", Instant.parse("2026-10-21T06:12:00Z"), today))
            val done = view("t-done", TaskData("Dishes", "m1", dueDate = today), TaskGroup.TODAY, completedToday = completion)
            val state =
                TasksUiState(
                    loaded = true,
                    filter = TaskFilter(scope = TaskScope.ALL),
                    sections =
                        listOf(
                            TaskSectionUi(TaskGroup.OVERDUE, 1, listOf(row(over))),
                            TaskSectionUi(TaskGroup.TODAY, 1, listOf(row(open), row(done, done = true))),
                        ),
                    counts = mapOf(TaskScope.ALL to 5, TaskScope.MINE to 2, TaskScope.ASSIGNED_BY_ME to 1),
                    canCreate = true,
                )
            setContent { LaresTheme(darkTheme = false) { TasksScreen(state) } }

            onNodeWithText("All · 5").assertIsDisplayed()
            onNodeWithText("Mine · 2").assertIsDisplayed()
            onNodeWithText("Overdue · 1").assertIsDisplayed()
            onNodeWithText("Today · 1").assertIsDisplayed()
            onNodeWithTag("task_meta_overdue_t-over", useUnmergedTree = true).assertTextEquals("Yesterday, 21:00")
            onNodeWithTag("task_meta_t-today", useUnmergedTree = true).assertTextEquals("Today")
            onNodeWithText("Car", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("1/3", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("task_title_t-done", useUnmergedTree = true).assertTextEquals("Dishes")
            onNodeWithTag("task_meta_t-done", useUnmergedTree = true).assertTextEquals("Boris · at 9:12")
            onNodeWithTag("task_check_t-done").assertIsOn()
            onNodeWithTag("task_check_t-today").assertIsOff()
            onNodeWithTag("tasks_fab").assertIsDisplayed()
        }

    @Test
    fun `empty Mine list`() =
        runComposeUiTest {
            setContent { LaresTheme(darkTheme = false) { TasksScreen(TasksUiState(loaded = true)) } }
            onNodeWithText("Nothing for you. Enjoy the day").assertIsDisplayed()
        }

    @Test
    fun `deleting a routine asks this occurrence or the whole series`() =
        runComposeUiTest {
            val routine =
                view(
                    "t-routine",
                    TaskData(
                        "Trash",
                        "m1",
                        dueDate = LocalDate(2026, 10, 15),
                        dueTime = LocalTime(20, 0),
                        recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.DAY),
                    ),
                    TaskGroup.TODAY,
                ).copy(state = TaskState(today, done = false, doneBy = null))
            val state =
                TaskDetailUiState(
                    loaded = true,
                    detail = TaskDetail(routine, emptyList(), emptyList(), null),
                    today = today,
                    zone = zone,
                    names = names,
                )
            setContent { LaresTheme(darkTheme = true) { TaskDetailScreen(state, newItem = null) } }

            onNodeWithTag("task_title").assertTextEquals("Trash")
            onNodeWithText("Every day").assertIsDisplayed()
            onNodeWithText("Next time: Tomorrow").assertIsDisplayed()
            onNodeWithText("Anyone").assertIsDisplayed()
            onNodeWithTag("task_more").performClick()
            onNodeWithText("Delete").performClick()
            onNodeWithText("Delete routine").assertIsDisplayed()
            onNodeWithText("Only this occurrence").assertIsDisplayed()
            onNodeWithText("The whole series").assertIsDisplayed()
        }
}
