package app.familyhub.contract.entities

import app.familyhub.contract.permissions.BuiltInGroup
import app.familyhub.contract.permissions.Permission
import app.familyhub.contract.permissions.PermissionSetSerializer
import app.familyhub.contract.serialization.LenientEnumSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable

// Entity payloads of the identity and household modules. They travel in sync (M0-05); ids and seq live in the envelope.

@Serializable
data class HouseholdData(
    val name: String,
    /** IANA time zone id, e.g. `Europe/Moscow`. */
    val timeZone: String,
    /** ISO 4217 code, e.g. `RUB`. */
    val currency: String,
    /** `en` or `ru`. */
    val defaultLanguage: String,
)

/**
 * A person: a member's profile or a relative without an account. The birth year may be unknown.
 * [color] is the personal color (FND-034); null for persons created before it existed, see `PaletteColors.fallbackFor`.
 * It stays the last field so that older payloads still decode.
 */
@Serializable
data class PersonData(
    val name: String,
    val birthYear: Int? = null,
    val birthMonth: Int? = null,
    val birthDay: Int? = null,
    val relation: String? = null,
    val notes: String? = null,
    val contacts: List<ContactData> = emptyList(),
    val color: PaletteColor? = null,
)

@Serializable
data class ContactData(
    val kind: ContactKind,
    val value: String,
)

/** Unknown kinds from a newer version decode to [OTHER]. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = ContactKindSerializer::class)
enum class ContactKind { PHONE, EMAIL, ADDRESS, OTHER }

@OptIn(ExperimentalSerializationApi::class)
internal object ContactKindSerializer :
    LenientEnumSerializer<ContactKind>(ContactKind.generatedSerializer(), ContactKind.entries, ContactKind.OTHER)

@Serializable
data class MemberData(
    val personId: String,
    val userId: String?,
    val login: String?,
    val hasPassword: Boolean,
)

/** A tag (TAG-001). The name is unique in the household, case-insensitive (TAG-006); archived tags keep their name. */
@Serializable
data class TagData(
    val name: String,
    val color: PaletteColor,
    val icon: String? = null,
    val archived: Boolean = false,
)

@Serializable
data class GroupData(
    val name: String,
    val builtIn: BuiltInGroup?,
    @Serializable(with = PermissionSetSerializer::class) val permissions: Set<Permission>,
    val memberIds: Set<String>,
)
