package app.lares.server.identity

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

data class UserRow(
    val id: UUID,
    val login: String?,
    val passwordHash: String?,
    val deleted: Boolean,
)

object UserRepository {
    fun create(
        id: UUID,
        login: String?,
        passwordHash: String?,
        now: Instant,
    ) {
        UserAccountTable.insert {
            it[UserAccountTable.id] = id
            it[UserAccountTable.login] = login
            it[UserAccountTable.passwordHash] = passwordHash
            it[createdAt] = now
        }
    }

    fun get(id: UUID): UserRow? =
        UserAccountTable
            .selectAll()
            .where { UserAccountTable.id eq id }
            .singleOrNull()
            ?.let(::toRow)

    fun findActiveByLogin(login: String): UserRow? =
        UserAccountTable
            .selectAll()
            .where { (UserAccountTable.login eq login) and UserAccountTable.deletedAt.isNull() }
            .singleOrNull()
            ?.let(::toRow)

    /** True if any user, active or not, holds this (normalized) login. */
    fun loginExists(login: String): Boolean =
        UserAccountTable
            .selectAll()
            .where { UserAccountTable.login eq login }
            .limit(1)
            .any()

    fun setPasswordHash(
        id: UUID,
        passwordHash: String,
    ) {
        UserAccountTable.update({ UserAccountTable.id eq id }) { it[UserAccountTable.passwordHash] = passwordHash }
    }

    /** FND-013: the login is freed and the password removed; the row stays for history. */
    fun markDeleted(
        id: UUID,
        now: Instant,
    ) {
        UserAccountTable.update({ UserAccountTable.id eq id }) {
            it[login] = "deleted-$id"
            it[passwordHash] = null
            it[deletedAt] = now
        }
    }

    private fun toRow(it: org.jetbrains.exposed.v1.core.ResultRow) =
        UserRow(
            id = it[UserAccountTable.id],
            login = it[UserAccountTable.login],
            passwordHash = it[UserAccountTable.passwordHash],
            deleted = it[UserAccountTable.deletedAt] != null,
        )
}
