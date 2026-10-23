package app.familyhub.client.data

import app.familyhub.contract.entities.GroupData
import app.familyhub.contract.entities.HouseholdData
import app.familyhub.contract.entities.MemberData
import app.familyhub.contract.entities.PaletteColor
import app.familyhub.contract.entities.PaletteColors
import app.familyhub.contract.entities.PersonData
import app.familyhub.contract.entities.TagData

// What the repositories expose: the synced entity data with its id.

data class Household(
    val id: String,
    val data: HouseholdData,
)

/** A member with the name and personal color of their person (FND-034). */
data class MemberView(
    val id: String,
    val data: MemberData,
    val name: String,
    val color: PaletteColor,
)

data class Group(
    val id: String,
    val data: GroupData,
)

data class Person(
    val id: String,
    val data: PersonData,
) {
    /** The personal color; persons created before FND-034 get a stable fallback. */
    val color: PaletteColor get() = data.color ?: PaletteColors.fallbackFor(id)
}

data class Tag(
    val id: String,
    val data: TagData,
)
