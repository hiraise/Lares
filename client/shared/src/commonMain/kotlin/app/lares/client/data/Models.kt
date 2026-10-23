package app.lares.client.data

import app.lares.contract.entities.GroupData
import app.lares.contract.entities.HouseholdData
import app.lares.contract.entities.MemberData
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.PaletteColors
import app.lares.contract.entities.PersonData
import app.lares.contract.entities.TagData

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
