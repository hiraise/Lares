package app.lares.client.ui.location

import app.lares.client.data.GroupsRepository
import app.lares.client.data.MembersRepository
import app.lares.client.data.location.ANYA
import app.lares.client.data.location.LocationFixture
import app.lares.client.data.location.OLGA
import app.lares.client.data.location.bodyText
import app.lares.client.data.location.json
import app.lares.client.ui.awaitState
import app.lares.contract.api.ApiError
import app.lares.contract.api.ApiErrorBody
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.api.location.LocationConfig
import app.lares.contract.api.location.LocationRule
import app.lares.contract.api.location.LocationRuleInput
import app.lares.contract.api.location.LocationRules
import app.lares.contract.api.location.PartyKind
import app.lares.contract.api.location.PutLocationRulesRequest
import app.lares.contract.api.location.RuleParty
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.permissions.Permission
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Family › «Кто кого видит» (plan M4-10, LOC-005, LOC-006): load, one PUT, row errors, offline, history period. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationRulesViewModelTest {
    private val f = LocationFixture()
    private val vms = TestViewModels()
    private val puts = mutableListOf<PutLocationRulesRequest>()
    private val patches = mutableListOf<String>()
    private var offline = false
    private var historyDays = 30
    private var putError: ApiErrorBody? = null
    private val kids = RuleParty(PartyKind.MEMBER, ANYA)
    private val stored = LocationRule("r1", RuleParty(PartyKind.EVERYONE), RuleParty(PartyKind.EVERYONE), VisibilityLevel.CURRENT)

    @BeforeTest
    fun main() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest
    fun close() {
        vms.clear()
        f.close()
        Dispatchers.resetMain()
    }

    private fun setUp(): LocationRulesViewModel {
        runBlocking {
            f.family()
            f.loginAs(OLGA, Permission.HOUSEHOLD_MANAGE, Permission.LOCATION_VIEW)
        }
        f.server = { request ->
            if (offline) throw IOException("no network")
            val path = request.url.encodedPath
            when {
                path == "/api/v1/location/rules" && request.method == HttpMethod.Get -> {
                    json(LocationRules.serializer(), LocationRules(listOf(stored)))
                }

                path == "/api/v1/location/rules" -> {
                    synchronized(puts) { puts += LaresJson.decodeFromString(PutLocationRulesRequest.serializer(), request.bodyText()) }
                    putError?.let { json(LaresJson.encodeToString(ApiErrorBody.serializer(), it), HttpStatusCode.BadRequest) }
                        ?: respond("", HttpStatusCode.NoContent)
                }

                path == "/api/v1/location/config" -> {
                    json(LocationConfig.serializer(), LocationConfig("light", "dark", addresses = true, historyDays = historyDays))
                }

                path == "/api/v1/household" -> {
                    synchronized(patches) { patches += request.bodyText() }
                    historyDays = 14
                    respond("", HttpStatusCode.NoContent)
                }

                else -> {
                    respond("", HttpStatusCode.NotFound)
                }
            }
        }
        return vms.keep(
            LocationRulesViewModel(
                f.repository(),
                f.api,
                f.engine,
                GroupsRepository(f.localDb),
                MembersRepository(f.localDb),
                f.permissions,
            ),
        )
    }

    @Test
    fun `LOC-006 loads the rules and saves the whole list with one PUT`() {
        val vm = setUp()
        val loaded = awaitState(vm.state) { it.loaded && it.historyDays == 30 }
        assertTrue(loaded.editable)
        assertEquals(1, loaded.rules.size)
        assertFalse(loaded.dirty)

        vm.openNew()
        vm.onSubject(kids)
        vm.onLevel(VisibilityLevel.HISTORY)
        vm.applyEditor()
        vm.openRule(0)
        vm.onTarget(kids)
        vm.applyEditor()
        awaitState(vm.state) { it.dirty && it.rules.size == 2 }
        vm.save()
        awaitState(vm.state) { !it.saving && !it.dirty }
        assertEquals(1, puts.size, "one PUT")
        assertEquals(
            listOf(
                LocationRuleInput(stored.subject, kids, stored.level),
                LocationRuleInput(kids, RuleParty(PartyKind.EVERYONE), VisibilityLevel.HISTORY),
            ),
            puts.single().rules,
        )
    }

    @Test
    fun `LOC-006 VALIDATION_FAILED is shown on its row`() {
        val vm = setUp()
        awaitState(vm.state) { it.loaded }
        putError =
            ApiErrorBody(ApiError(ErrorCode.VALIDATION_FAILED, "test", mapOf("rules.1.subject" to FieldErrorCode.INVALID_FORMAT)))
        vm.openNew()
        vm.onSubject(kids)
        vm.applyEditor()
        vm.save()
        val failed = awaitState(vm.state) { !it.saving && it.rowErrors.isNotEmpty() }
        assertEquals(setOf(1), failed.rowErrors.keys)
        assertTrue(failed.dirty, "the list stays for fixing")
    }

    @Test
    fun `LOC-006 offline the screen is read-only`() {
        offline = true
        val vm = setUp()
        val state = awaitState(vm.state) { it.loadFailed }
        assertFalse(state.editable)
        assertFalse(state.loaded)
        vm.openNew()
        assertEquals(null, vm.state.value.editor)
    }

    @Test
    fun `LOC-005 history days are saved and read again from the server`() {
        val vm = setUp()
        awaitState(vm.state) { it.historyDays == 30 && it.loaded }
        vm.setHistoryDays(14)
        awaitState(vm.state) { it.historyDays == 14 && !it.historySaving }
        assertEquals(1, patches.size)
        assertTrue(patches.single().contains("\"locationHistoryDays\":14"), patches.single())
    }
}
