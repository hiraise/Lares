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
     * members may always edit their own profile. For `task`, `checklist_item` and `task_completion` this is only the
     * coarse `tasks.view` gate; the entity-level decision is [TaskRules]. `shopping_list` needs `shopping.manage_lists` and
     * `shopping_item` `shopping.edit`; the entity-level checks are [ShoppingRules]. `event` needs `calendar.view`;
     * `event_occurrence` and `event_reminder` too, but are never deleted by clients (ADR-0022, ADR-0023); the
     * entity-level checks are [EventRules]. Types not listed here (including read-only
     * `household`, `member`, `group`) cannot be changed through sync.
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

            EntityTypes.TASK, EntityTypes.CHECKLIST_ITEM, EntityTypes.TASK_COMPLETION -> {
                permissions.has(Permission.TASKS_VIEW)
            }

            EntityTypes.SHOPPING_LIST -> {
                permissions.has(Permission.SHOPPING_MANAGE_LISTS)
            }

            EntityTypes.SHOPPING_ITEM -> {
                permissions.has(Permission.SHOPPING_EDIT)
            }

            EntityTypes.EVENT -> {
                permissions.has(Permission.CALENDAR_VIEW)
            }

            EntityTypes.EVENT_OCCURRENCE, EntityTypes.EVENT_REMINDER -> {
                op == MutationOp.UPSERT && permissions.has(Permission.CALENDAR_VIEW)
            }

            else -> {
                false
            }
        }
    }
}
