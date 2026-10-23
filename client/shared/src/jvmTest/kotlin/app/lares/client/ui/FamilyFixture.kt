package app.lares.client.ui

import app.lares.client.sync.SyncEngine
import app.lares.contract.api.LaresJson
import app.lares.contract.api.auth.Me
import app.lares.contract.entities.GroupData
import app.lares.contract.entities.HouseholdData
import app.lares.contract.entities.MemberData
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.PersonData
import app.lares.contract.entities.TagData
import app.lares.contract.permissions.BuiltInGroup
import app.lares.contract.permissions.PermissionCatalogue
import app.lares.contract.sync.Change
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.PullResponse
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonObject

/**
 * A synced household for Family tests: Olga (m1, Administrators), Boris (m2, Adults), Carol (m3, Kids),
 * relative Grandma (p4, not a member) and one tag.
 */
object FamilyFixture {
    const val ADMINS = "g-admins"
    const val ADULTS = "g-adults"
    const val KIDS = "g-kids"

    val olga = testMe.copy(isAdmin = true)
    val boris = testMe.copy(userId = "u2", memberId = "m2", personId = "p2", displayName = "Boris", login = "boris", isAdmin = false)
    val carol = testMe.copy(userId = "u3", memberId = "m3", personId = "p3", displayName = "Carol", login = null, isAdmin = false)

    private var seq = 0L

    private fun <T> change(
        type: String,
        id: String,
        serializer: KSerializer<T>,
        data: T,
    ) = Change(type, id, ++seq, false, LaresJson.encodeToJsonElement(serializer, data).jsonObject)

    val changes: List<Change> =
        listOf(
            change(EntityTypes.HOUSEHOLD, "h1", HouseholdData.serializer(), HouseholdData("Petrovy", "Europe/Moscow", "RUB", "ru")),
            change(EntityTypes.PERSON, "p1", PersonData.serializer(), PersonData("Olga", color = PaletteColor.PINK)),
            change(EntityTypes.PERSON, "p2", PersonData.serializer(), PersonData("Boris", color = PaletteColor.GREEN)),
            change(EntityTypes.PERSON, "p3", PersonData.serializer(), PersonData("Carol", color = PaletteColor.PURPLE)),
            change(
                EntityTypes.PERSON,
                "p4",
                PersonData.serializer(),
                PersonData("Grandma", birthMonth = 3, birthDay = 8, relation = "grandmother", color = PaletteColor.ORANGE),
            ),
            change(EntityTypes.MEMBER, "m1", MemberData.serializer(), MemberData("p1", "u1", "olga", true)),
            change(EntityTypes.MEMBER, "m2", MemberData.serializer(), MemberData("p2", "u2", "boris", true)),
            change(EntityTypes.MEMBER, "m3", MemberData.serializer(), MemberData("p3", "u3", null, false)),
            change(
                EntityTypes.GROUP,
                ADMINS,
                GroupData.serializer(),
                GroupData("Administrators", BuiltInGroup.ADMINISTRATORS, emptySet(), setOf("m1")),
            ),
            change(
                EntityTypes.GROUP,
                ADULTS,
                GroupData.serializer(),
                GroupData("Adults", null, PermissionCatalogue.adultsTemplate, setOf("m2")),
            ),
            change(EntityTypes.GROUP, KIDS, GroupData.serializer(), GroupData("Kids", null, PermissionCatalogue.kidsTemplate, setOf("m3"))),
            change(EntityTypes.TAG, "t1", TagData.serializer(), TagData("Car", PaletteColor.BLUE)),
        )

    fun MockRequestHandleScope.pull() =
        json(PullResponse.serializer(), PullResponse(false, 1, "epoch", changes, changes.maxOf { it.seq }, false))

    /**
     * A logged-in core as [me] with the fixture synced. [extra] answers requests first; sync push and anything else
     * unknown answers 503.
     */
    fun core(
        me: Me = olga,
        extra: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData? = { null },
    ): UiTestCore {
        val core =
            UiTestCore { request ->
                extra(request) ?: when (request.url.encodedPath) {
                    "/api/v1/instance" -> instance()
                    "/api/v1/auth/login" -> authResult(me)
                    "/api/v1/sync/changes" -> pull()
                    else -> unavailable()
                }
            }
        runBlocking {
            core.session.connect("https://family.test")
            core.session.login(me.login ?: "user", "secret123")
            core.koin.get<SyncEngine>().syncNow()
        }
        return core
    }
}
