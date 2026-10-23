package app.familyhub.client.ui.family

import app.familyhub.client.data.Group
import app.familyhub.client.data.MemberView
import app.familyhub.client.data.Person
import app.familyhub.client.data.Tag
import app.familyhub.client.resources.Res
import app.familyhub.client.resources.error_tag_name_taken
import app.familyhub.client.resources.family_birthday_incomplete
import app.familyhub.client.sync.SyncState
import app.familyhub.client.sync.SyncStatus
import app.familyhub.client.ui.common.FieldError
import app.familyhub.contract.api.FieldErrorCode
import app.familyhub.contract.entities.ContactData
import app.familyhub.contract.entities.PaletteColor
import app.familyhub.contract.entities.PersonData
import app.familyhub.contract.permissions.BuiltInGroup
import app.familyhub.contract.permissions.EffectivePermissions
import app.familyhub.contract.permissions.Permission
import app.familyhub.contract.permissions.PermissionCatalogue
import app.familyhub.contract.permissions.PermissionKind
import app.familyhub.contract.permissions.Section
import app.familyhub.contract.validation.Validators

/** Tabs of the Family screen. */
enum class FamilyTab { MEMBERS, RELATIVES, GROUPS, TAGS }

/** What the current member may change in the Family area (ADR-0014); every edit control follows it. */
data class FamilyAccess(
    /** `household.manage`: members, groups, invites, login codes, family settings. */
    val manageFamily: Boolean = false,
    val managePersons: Boolean = false,
    val manageTags: Boolean = false,
) {
    companion object {
        fun of(permissions: EffectivePermissions) =
            FamilyAccess(
                manageFamily = permissions.has(Permission.HOUSEHOLD_MANAGE),
                managePersons = permissions.has(Permission.PERSONS_MANAGE),
                manageTags = permissions.has(Permission.TAGS_MANAGE),
            )
    }
}

/** Admin actions call the server directly, so they are off while the sync engine reports no connection. */
fun SyncStatus.isOnline(): Boolean = state != SyncState.OFFLINE

/** A group as the Family screens show it; the Administrators group is labelled by the app. */
data class GroupItem(
    val id: String,
    val name: String,
    val builtIn: BuiltInGroup?,
    val permissions: Set<Permission>,
    val memberIds: Set<String>,
) {
    val isAdministrators: Boolean get() = builtIn == BuiltInGroup.ADMINISTRATORS

    companion object {
        /** Administrators first, then by name. */
        fun list(groups: List<Group>): List<GroupItem> =
            groups
                .map { GroupItem(it.id, it.data.name, it.data.builtIn, it.data.permissions, it.data.memberIds) }
                .sortedWith(compareBy({ !it.isAdministrators }, { it.name.lowercase() }))
    }
}

data class MemberItem(
    val id: String,
    val personId: String,
    val name: String,
    val color: PaletteColor,
    val login: String?,
    val hasPassword: Boolean,
    /** The member's groups, Administrators first. */
    val groups: List<GroupItem>,
    val isMe: Boolean,
) {
    val isAdmin: Boolean get() = groups.any { it.isAdministrators }

    companion object {
        /** The current member first, then by name. */
        fun list(
            members: List<MemberView>,
            groups: List<GroupItem>,
            myMemberId: String?,
        ): List<MemberItem> =
            members
                .map { member ->
                    MemberItem(
                        id = member.id,
                        personId = member.data.personId,
                        name = member.name,
                        color = member.color,
                        login = member.data.login,
                        hasPassword = member.data.hasPassword,
                        groups = groups.filter { member.id in it.memberIds },
                        isMe = member.id == myMemberId,
                    )
                }.sortedWith(compareBy({ !it.isMe }, { it.name.lowercase() }))
    }
}

/** A person in the Relatives tab; members' profiles carry a "member" badge and cannot be deleted. */
data class PersonItem(
    val person: Person,
    val isMember: Boolean,
    val isMe: Boolean,
) {
    companion object {
        fun list(
            persons: List<Person>,
            members: List<MemberView>,
            myPersonId: String?,
        ): List<PersonItem> {
            val memberPersons = members.map { it.data.personId }.toSet()
            return persons
                .map { PersonItem(it, it.id in memberPersons, it.id == myPersonId) }
                .sortedBy {
                    it.person.data.name
                        .lowercase()
                }
        }

        /** Relatives that are not linked to a member yet: candidates for a new account (FND-032). */
        fun unlinked(
            persons: List<Person>,
            members: List<MemberView>,
        ): List<Person> {
            val linked = members.map { it.data.personId }.toSet()
            return persons.filter { it.id !in linked }.sortedBy { it.data.name.lowercase() }
        }
    }
}

/** The permission editor (plan M0-08): sections with their permissions; checking an action checks its "See …". */
object PermissionChecklist {
    /** `null` is the Family section: permissions outside any app section. */
    val sections: List<Pair<Section?, List<Permission>>> =
        listOf(null to listOf(Permission.PERSONS_MANAGE, Permission.TAGS_MANAGE, Permission.HOUSEHOLD_MANAGE)) +
            Section.entries.map { section ->
                val view = PermissionCatalogue.sectionPermission(section)
                section to (listOf(view) + Permission.entries.filter { it.section == section && it != view })
            }

    /** Applies one checkbox change; mirrors `PermissionCatalogue.withImplied` in both directions. */
    fun toggle(
        current: Set<Permission>,
        permission: Permission,
        checked: Boolean,
    ): Set<Permission> {
        val section = permission.section
        return if (checked) {
            if (permission.kind == PermissionKind.ACTION && section != null) {
                current + permission + PermissionCatalogue.sectionPermission(section)
            } else {
                current + permission
            }
        } else {
            if (permission.kind == PermissionKind.SECTION && section != null) {
                current.filterNot { it == permission || it.section == section }.toSet()
            } else {
                current - permission
            }
        }
    }
}

/** Tag rules checked before a write (TAG-006): names are unique case-insensitively, archived tags included. */
object TagRules {
    fun nameError(
        name: String,
        tags: List<Tag>,
        exceptId: String?,
    ): FieldError? {
        Validators.tagName(name)?.let { return FieldError.of(it) }
        val taken =
            tags.any {
                it.id != exceptId &&
                    it.data.name
                        .trim()
                        .equals(name.trim(), ignoreCase = true)
            }
        return if (taken) FieldError(Res.string.error_tag_name_taken) else null
    }
}

/** The editable fields of the person form (FND-031, FND-034). */
data class PersonForm(
    val name: String = "",
    val birthDay: Int? = null,
    val birthMonth: Int? = null,
    val birthYear: Int? = null,
    val relation: String = "",
    val notes: String = "",
    val contacts: List<ContactData> = emptyList(),
    val color: PaletteColor? = null,
) {
    /** The person data to save; blank optional fields become null and empty contacts are dropped. */
    fun toData(): PersonData =
        PersonData(
            name = name.trim(),
            birthYear = birthYear,
            birthMonth = birthMonth,
            birthDay = birthDay,
            relation = relation.trim().ifEmpty { null },
            notes = notes.trim().ifEmpty { null },
            contacts = contacts.map { it.copy(value = it.value.trim()) }.filter { it.value.isNotEmpty() },
            color = color,
        )

    /**
     * Field errors keyed by `name`, `birthday`, `relation`, `notes`, `contacts`. Day and month go together; the year
     * is optional; February 29 without a year is fine.
     */
    fun validate(currentYear: Int): Map<String, FieldError> {
        val errors = LinkedHashMap<String, FieldError>()
        val data = toData()
        if ((birthDay == null) != (birthMonth == null) || (birthYear != null && birthDay == null)) {
            errors[BIRTHDAY] = FieldError(Res.string.family_birthday_incomplete)
        }
        for ((field, code) in Validators.person(data, currentYear)) {
            val key = if (field.startsWith("birth")) BIRTHDAY else field
            if (key !in errors) errors[key] = FieldError.of(code) ?: FieldError.of(FieldErrorCode.UNKNOWN)!!
        }
        return errors
    }

    companion object {
        const val BIRTHDAY = "birthday"

        fun of(data: PersonData) =
            PersonForm(
                name = data.name,
                birthDay = data.birthDay,
                birthMonth = data.birthMonth,
                birthYear = data.birthYear,
                relation = data.relation.orEmpty(),
                notes = data.notes.orEmpty(),
                contacts = data.contacts,
                color = data.color,
            )
    }
}
