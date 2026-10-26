package app.lares.contract.api.location

import app.lares.contract.serialization.LenientEnumSerializer
import kotlinx.datetime.LocalDate
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// REST and live-channel DTOs of the location module (architecture/location.md § REST DTOs, ADR-0024, ADR-0025).

/** One location fix of the sharing phone. */
@Serializable
data class PositionFix(
    /** Device time of the fix, UTC. */
    val recordedAt: Instant,
    val latitude: Double,
    val longitude: Double,
    /** 0..10000. */
    val accuracyM: Float,
    /** 0..100. */
    val speedMps: Float? = null,
    /** 0..360. */
    val bearingDeg: Float? = null,
    val altitudeM: Double? = null,
    /** 0..100. */
    val battery: Int? = null,
    val charging: Boolean? = null,
)

/** `POST /api/v1/location/positions`: 1..200 fixes in any order. */
@Serializable
data class UploadPositionsRequest(
    val positions: List<PositionFix>,
)

@Serializable
data class UploadPositionsResponse(
    val accepted: Int,
    /** False: another phone of the member shares, or the member is not sharing — stop sharing on this phone. */
    val sharingHere: Boolean,
    /** The member is paused; the positions were dropped. */
    val paused: Boolean = false,
    val pausedUntil: Instant? = null,
    /** Somebody pressed «Обновить» and no fix was received since. */
    val refreshRequested: Boolean = false,
)

/** Sharing state of a member (LOC-012). Unknown values decode to [NEVER]. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = SharingStatusSerializer::class)
enum class SharingStatus {
    @SerialName("never")
    NEVER,

    @SerialName("sharing")
    SHARING,

    @SerialName("paused")
    PAUSED,

    @SerialName("off_on_phone")
    OFF_ON_PHONE,
    ;

    val key: String get() = SharingStatusSerializer.serialName(this)
}

@OptIn(ExperimentalSerializationApi::class)
internal object SharingStatusSerializer :
    LenientEnumSerializer<SharingStatus>(SharingStatus.generatedSerializer(), SharingStatus.entries, SharingStatus.NEVER)

/** Why sharing is off on the phone. Unknown values decode to [STOPPED]. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = OffReasonSerializer::class)
enum class OffReason {
    @SerialName("location_disabled")
    LOCATION_DISABLED,

    @SerialName("permission_revoked")
    PERMISSION_REVOKED,

    @SerialName("stopped")
    STOPPED,

    @SerialName("signed_out")
    SIGNED_OUT,
    ;

    val key: String get() = OffReasonSerializer.serialName(this)
}

@OptIn(ExperimentalSerializationApi::class)
internal object OffReasonSerializer :
    LenientEnumSerializer<OffReason>(OffReason.generatedSerializer(), OffReason.entries, OffReason.STOPPED)

/** `PUT /api/v1/location/sharing`. */
@Serializable
data class SetSharingRequest(
    /** SHARING, PAUSED or OFF_ON_PHONE. */
    val status: SharingStatus,
    /** PAUSED only; null = until resumed; in the future, at most 7 days ahead. */
    val pausedUntil: Instant? = null,
    /** OFF_ON_PHONE only, required there. */
    val reason: OffReason? = null,
)

/** What a viewer may see of a member (ADR-0025), ordered from least to most. Unknown values decode to [NONE]. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = VisibilityLevelSerializer::class)
enum class VisibilityLevel {
    @SerialName("none")
    NONE,

    @SerialName("current")
    CURRENT,

    @SerialName("history")
    HISTORY,
    ;

    val key: String get() = VisibilityLevelSerializer.serialName(this)
}

@OptIn(ExperimentalSerializationApi::class)
internal object VisibilityLevelSerializer :
    LenientEnumSerializer<VisibilityLevel>(VisibilityLevel.generatedSerializer(), VisibilityLevel.entries, VisibilityLevel.NONE)

/** A member as one viewer sees them. */
@Serializable
data class MemberLocation(
    val memberId: String,
    /** CURRENT or HISTORY: the viewer's level for this member. */
    val level: VisibilityLevel,
    val status: SharingStatus,
    val pausedUntil: Instant? = null,
    val offReason: OffReason? = null,
    val statusSince: Instant? = null,
    /** Newest stored fix; while paused or off, the last one before. */
    val last: PositionFix? = null,
    /** A place the member is inside (the smallest radius wins). */
    val placeId: String? = null,
    val placeSince: Instant? = null,
)

/** `GET /api/v1/location/now` and the live snapshot. */
@Serializable
data class LocationNow(
    val members: List<MemberLocation>,
    val serverTime: Instant,
)

@Serializable
data class TrackPoint(
    val recordedAt: Instant,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
)

/** `GET /api/v1/location/members/{id}/history?date=`: one day in household time (LOC-004). */
@Serializable
data class LocationHistory(
    val memberId: String,
    val date: LocalDate,
    val points: List<TrackPoint>,
)

/** A message of `/api/v1/location/live`; [type] tells which field is set. */
@Serializable
data class LiveMessage(
    val type: String,
    val now: LocationNow? = null,
    val member: MemberLocation? = null,
    /** [GONE]: the member is no longer visible. */
    val memberId: String? = null,
) {
    companion object {
        const val SNAPSHOT = "snapshot"
        const val MEMBER = "member"
        const val GONE = "gone"
    }
}

/** The subject or target of a visibility rule; [id] is a group or member id, null for EVERYONE. */
@Serializable
data class RuleParty(
    val kind: PartyKind,
    val id: String? = null,
)

/** Unknown kinds decode to [UNKNOWN]; a rule with such a party matches nothing. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = PartyKindSerializer::class)
enum class PartyKind {
    @SerialName("everyone")
    EVERYONE,

    @SerialName("group")
    GROUP,

    @SerialName("member")
    MEMBER,

    @SerialName("unknown")
    UNKNOWN,
    ;

    val key: String get() = PartyKindSerializer.serialName(this)
}

@OptIn(ExperimentalSerializationApi::class)
internal object PartyKindSerializer :
    LenientEnumSerializer<PartyKind>(PartyKind.generatedSerializer(), PartyKind.entries, PartyKind.UNKNOWN)

/** "[subject] sees [target] at [level]" (ADR-0025). */
@Serializable
data class LocationRule(
    val id: String,
    val subject: RuleParty,
    val target: RuleParty,
    val level: VisibilityLevel,
)

/** `GET /api/v1/location/rules`. */
@Serializable
data class LocationRules(
    val rules: List<LocationRule>,
)

@Serializable
data class LocationRuleInput(
    val subject: RuleParty,
    val target: RuleParty,
    val level: VisibilityLevel,
)

/** `PUT /api/v1/location/rules`: at most 100 rules; replaces all. */
@Serializable
data class PutLocationRulesRequest(
    val rules: List<LocationRuleInput>,
)

@Serializable
data class Watcher(
    val memberId: String,
    val level: VisibilityLevel,
)

@Serializable
data class AlertWatcher(
    val memberId: String,
    val placeIds: Set<String>,
)

/** Who sees a member and who gets their arrive / leave alerts (transparency, LOC-006, LOC-021). */
@Serializable
data class LocationWatchers(
    val viewers: List<Watcher>,
    val alerts: List<AlertWatcher>,
)

/** `GET /api/v1/location/config`. */
@Serializable
data class LocationConfig(
    val mapStyleUrl: String,
    val mapDarkStyleUrl: String,
    /** The address service is configured. */
    val addresses: Boolean,
    val historyDays: Int,
)

/** Reverse lookup result; null label = unknown. */
@Serializable
data class AddressLabel(
    val label: String? = null,
)

@Serializable
data class AddressResult(
    val label: String,
    val latitude: Double,
    val longitude: Double,
)

@Serializable
data class AddressSearch(
    val results: List<AddressResult>,
)
