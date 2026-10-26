package app.lares.contract.permissions

import app.lares.contract.entities.PlaceAlertData
import app.lares.contract.entities.PlaceData

/**
 * Entity-level write rules of places and arrive / leave alerts (location spec § Permissions and privacy,
 * architecture/location.md § Write rules). Places are family data without ownership.
 */
object PlaceRules {
    /** Create, edit and delete any place: `location.manage_places`. */
    fun canManage(viewer: Viewer): Boolean = viewer.permissions.has(Permission.LOCATION_MANAGE_PLACES)

    /** Create a place: [canManage], with the viewer as creator. */
    fun canCreate(
        viewer: Viewer,
        place: PlaceData,
    ): Boolean = canManage(viewer) && place.createdBy == viewer.memberId

    /** Own alerts about another member: `location.view` (ADR-0023 pattern). Seeing that member is the server's check. */
    fun canSetAlert(
        viewer: Viewer,
        alert: PlaceAlertData,
    ): Boolean =
        viewer.permissions.has(Permission.LOCATION_VIEW) &&
            alert.subscriberId == viewer.memberId &&
            alert.memberId != viewer.memberId
}
