package app.familyhub.contract.permissions

import app.familyhub.contract.serialization.LenientEnumSerializer
import app.familyhub.contract.serialization.LenientEnumSetSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** App sections that a member may or may not see (ADR-0014, section access). */
enum class Section { TASKS, SHOPPING, CALENDAR, MAP, BUDGET }

enum class PermissionKind { SECTION, ACTION, MANAGEMENT }

/**
 * The permission catalogue (foundation spec § Permission catalogue). The serial name is the catalogue key,
 * also stored in the database (`group_permission.permission`).
 * [section] is the section an action belongs to; `null` for permissions outside any section.
 */
@Serializable
enum class Permission(
    val kind: PermissionKind,
    val section: Section?,
) {
    @SerialName("household.manage")
    HOUSEHOLD_MANAGE(PermissionKind.MANAGEMENT, null),

    @SerialName("persons.manage")
    PERSONS_MANAGE(PermissionKind.ACTION, null),

    @SerialName("tags.manage")
    TAGS_MANAGE(PermissionKind.ACTION, null),

    @SerialName("tasks.view")
    TASKS_VIEW(PermissionKind.SECTION, Section.TASKS),

    @SerialName("tasks.create")
    TASKS_CREATE(PermissionKind.ACTION, Section.TASKS),

    @SerialName("tasks.edit_any")
    TASKS_EDIT_ANY(PermissionKind.ACTION, Section.TASKS),

    @SerialName("shopping.view")
    SHOPPING_VIEW(PermissionKind.SECTION, Section.SHOPPING),

    @SerialName("shopping.edit")
    SHOPPING_EDIT(PermissionKind.ACTION, Section.SHOPPING),

    @SerialName("shopping.manage_lists")
    SHOPPING_MANAGE_LISTS(PermissionKind.ACTION, Section.SHOPPING),

    @SerialName("calendar.view")
    CALENDAR_VIEW(PermissionKind.SECTION, Section.CALENDAR),

    @SerialName("calendar.create")
    CALENDAR_CREATE(PermissionKind.ACTION, Section.CALENDAR),

    @SerialName("calendar.edit_any")
    CALENDAR_EDIT_ANY(PermissionKind.ACTION, Section.CALENDAR),

    @SerialName("location.view")
    LOCATION_VIEW(PermissionKind.SECTION, Section.MAP),

    @SerialName("location.pause_own")
    LOCATION_PAUSE_OWN(PermissionKind.ACTION, Section.MAP),

    @SerialName("location.manage_places")
    LOCATION_MANAGE_PLACES(PermissionKind.ACTION, Section.MAP),

    @SerialName("budget.view")
    BUDGET_VIEW(PermissionKind.SECTION, Section.BUDGET),

    @SerialName("budget.manage")
    BUDGET_MANAGE(PermissionKind.ACTION, Section.BUDGET),
    ;

    /** The catalogue key, e.g. `tasks.edit_any`. */
    val key: String get() = PermissionElementSerializer.serialName(this)

    companion object {
        /** Returns the permission with this catalogue key, or `null` if this version does not know it. */
        fun fromKey(key: String): Permission? = PermissionElementSerializer.decodeOrNull(key)
    }
}

/** Element serializer for [PermissionSetSerializer]; it has no fallback, so a single unknown permission fails to decode. */
internal object PermissionElementSerializer :
    LenientEnumSerializer<Permission>(Permission.serializer(), Permission.entries, fallback = null)

/** Serializer for every `Set<Permission>` in DTOs and entities: permissions unknown to this version are dropped. */
object PermissionSetSerializer : LenientEnumSetSerializer<Permission>(PermissionElementSerializer)

/** Built-in groups. Clients identify them by this flag, not by the stored (localized) name. */
@Serializable
enum class BuiltInGroup { ADMINISTRATORS, }
