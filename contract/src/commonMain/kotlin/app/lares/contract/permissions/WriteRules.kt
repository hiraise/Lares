package app.lares.contract.permissions

import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.MutationOp

/** The member making a change, as the write rules need it. */
data class Viewer(
    val memberId: String,
    val personId: String,
    val permissions: EffectivePermissions,
)

/**
 * Who may change which entity type through sync (ADR-0014). The server checks every mutation with these rules
 * (`FORBIDDEN`); the client checks the same rules before a local write.
 */
object WriteRules {
    /**
     * [existingPersonOwner] is true when the mutation targets an existing person that is the viewer's own profile;
     * members may always edit their own profile. Types not listed here (including read-only `household`, `member`,
     * `group`) cannot be changed through sync.
     */
    fun canMutate(
        type: String,
        op: MutationOp,
        viewer: Viewer,
        existingPersonOwner: Boolean,
    ): Boolean {
        val permissions = viewer.permissions
        return when (type) {
            EntityTypes.TAG -> {
                permissions.has(Permission.TAGS_MANAGE)
            }

            EntityTypes.PERSON -> {
                when (op) {
                    MutationOp.UPSERT -> permissions.has(Permission.PERSONS_MANAGE) || existingPersonOwner
                    MutationOp.DELETE -> permissions.has(Permission.PERSONS_MANAGE)
                }
            }

            else -> {
                false
            }
        }
    }
}
