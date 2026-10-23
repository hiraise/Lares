package app.familyhub.client.sync

import app.familyhub.client.db.FamilyHubDb
import app.familyhub.contract.entities.PaletteColor
import app.familyhub.contract.entities.TagData
import app.familyhub.contract.sync.EntityTypes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

class MaterializerTest {
    private val localDb = testLocalDb()
    private val adapters = defaultEntityAdapters(Clock.System)
    private val materializer = Materializer(adapters)
    private val id = "01928c4e-0000-7000-8000-000000000001"

    private fun FamilyHubDb.shadow(
        data: String?,
        deleted: Boolean = false,
    ) = sync_shadowQueries.upsert(EntityTypes.TAG, id, 1, if (deleted) 1 else 0, data)

    private var mutationNumber = 0

    private fun FamilyHubDb.pending(
        op: String,
        fields: String?,
    ) = sync_outboxQueries.enqueue("m-${mutationNumber++}", EntityTypes.TAG, id, op, fields, "2026-10-22T10:00:00Z")

    private suspend fun materialize(setup: FamilyHubDb.() -> Unit): TagData? =
        localDb.transaction {
            setup()
            materializer.rematerialize(this, EntityTypes.TAG, id)
            adapters[EntityTypes.TAG]!!.selectTyped(this, id) as TagData?
        }

    @Test
    fun shadowOnly() =
        runTest {
            val tag = materialize { shadow("""{"name":"Car","color":"blue","archived":false}""") }
            assertEquals(TagData("Car", PaletteColor.BLUE), tag)
        }

    @Test
    fun shadowWithTwoPatchesInOrder() =
        runTest {
            val tag =
                materialize {
                    shadow("""{"name":"Car","color":"blue","archived":false}""")
                    pending("upsert", """{"name":"Auto"}""")
                    pending("upsert", """{"name":"Vehicle","color":"red"}""")
                }
            assertEquals(TagData("Vehicle", PaletteColor.RED), tag)
        }

    @Test
    fun pendingDeleteRemovesTheRow() =
        runTest {
            val tag =
                materialize {
                    shadow("""{"name":"Car","color":"blue","archived":false}""")
                    pending("upsert", """{"name":"Auto"}""")
                    pending("delete", null)
                }
            assertNull(tag)
        }

    @Test
    fun tombstonedShadowRemovesTheRowDespitePendingPatches() =
        runTest {
            localDb.transaction {
                adapters[EntityTypes.TAG]!!.upsertFromJson(
                    this,
                    id,
                    TagAdapter().encode(TagData("Car", PaletteColor.BLUE)),
                )
            }
            val tag =
                materialize {
                    shadow(null, deleted = true)
                    pending("upsert", """{"name":"Auto"}""")
                }
            assertNull(tag)
        }

    @Test
    fun newEntityWithoutShadow() =
        runTest {
            val tag = materialize { pending("upsert", """{"name":"Garden","color":"green","archived":false}""") }
            assertEquals(TagData("Garden", PaletteColor.GREEN), tag)
        }

    @Test
    fun nothingKnownMeansNoRow() =
        runTest {
            assertNull(materialize { })
        }
}
