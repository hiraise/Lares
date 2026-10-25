package app.lares.server.location

import app.lares.server.testing.TestDatabase
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V9 adds the default visibility rules to households that existed before it (ADR-0025). */
class V9MigrationTest {
    private val config = TestDatabase.dbConfig

    /** The container's JDBC URL with another database name. */
    private val testUrl = config.url.replace(Regex("/[^/?]+(\\?|$)"), "/v9_test$1")

    private fun connect(): Connection = DriverManager.getConnection(testUrl, config.user, config.password)

    private fun flyway(target: String?) =
        Flyway
            .configure()
            .dataSource(testUrl, config.user, config.password)
            .locations("classpath:db/migration")
            .apply { if (target != null) target(target) }
            .load()

    private fun Connection.run(
        sql: String,
        vararg args: Any,
    ) = prepareStatement(sql).use { statement ->
        args.forEachIndexed { i, arg -> statement.setObject(i + 1, arg) }
        statement.execute()
    }

    private fun Connection.household(): UUID {
        val id = UUID.randomUUID()
        run(
            "INSERT INTO household (id, name, time_zone, currency, default_language, seq, created_at, updated_at) " +
                "VALUES (?, 'Family', 'Europe/Moscow', 'RUB', 'ru', 0, now(), now())",
            id,
        )
        return id
    }

    private fun Connection.group(
        household: UUID,
        name: String,
        deleted: Boolean = false,
        createdAt: String = "2026-01-02T00:00:00Z",
    ): UUID {
        val id = UUID.randomUUID()
        run(
            "INSERT INTO group_entity (id, household_id, name, seq, created_at, updated_at, deleted) " +
                "VALUES (?, ?, ?, 0, ?::timestamptz, now(), ?)",
            id,
            household,
            name,
            createdAt,
            deleted,
        )
        return id
    }

    private data class Rule(
        val subjectKind: String,
        val subjectGroup: UUID?,
        val targetKind: String,
        val targetGroup: UUID?,
        val level: String,
    )

    private fun Connection.rules(household: UUID): List<Rule> =
        prepareStatement(
            "SELECT subject_kind, subject_group_id, target_kind, target_group_id, level FROM location_rule " +
                "WHERE household_id = ? ORDER BY position",
        ).use { statement ->
            statement.setObject(1, household)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(Rule(rs.getString(1), rs.getObject(2) as UUID?, rs.getString(3), rs.getObject(4) as UUID?, rs.getString(5)))
                    }
                }
            }
        }

    @Test
    fun adr0025DefaultRulesForExistingHouseholds() {
        TestDatabase.dataSource.connection.use { admin ->
            admin.createStatement().use {
                it.execute("DROP DATABASE IF EXISTS v9_test WITH (FORCE)")
                it.execute("CREATE DATABASE v9_test")
            }
        }
        try {
            flyway(target = "8").migrate()
            val (russian, adultsRu, kidsRu, english, adultsEn, kidsEn, alone) =
                connect().use { db ->
                    val russian = db.household()
                    db.group(russian, "Взрослые", deleted = true, createdAt = "2026-01-01T00:00:00Z")
                    val adultsRu = db.group(russian, "Взрослые")
                    val kidsRu = db.group(russian, "Дети")
                    db.group(russian, "дети", createdAt = "2026-03-01T00:00:00Z") // newer duplicate: the oldest wins
                    val english = db.household()
                    val adultsEn = db.group(english, "adults")
                    val kidsEn = db.group(english, "KIDS")
                    val alone = db.household()
                    db.group(alone, "Adults")
                    Households(russian, adultsRu, kidsRu, english, adultsEn, kidsEn, alone)
                }
            flyway(target = null).migrate()
            connect().use { db ->
                assertEquals(
                    listOf(Rule("group", adultsRu, "everyone", null, "history"), Rule("group", kidsRu, "group", adultsRu, "current")),
                    db.rules(russian),
                )
                assertEquals(
                    listOf(Rule("group", adultsEn, "everyone", null, "history"), Rule("group", kidsEn, "group", adultsEn, "current")),
                    db.rules(english),
                )
                assertTrue(db.rules(alone).isEmpty(), "no Kids group, no rules")
                db.prepareStatement("SELECT location_history_days FROM household WHERE id = ?").use { statement ->
                    statement.setObject(1, russian)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        assertEquals(30, rs.getInt(1))
                    }
                }
            }
        } finally {
            TestDatabase.dataSource.connection.use { admin ->
                admin.createStatement().use { it.execute("DROP DATABASE IF EXISTS v9_test WITH (FORCE)") }
            }
        }
    }

    private data class Households(
        val russian: UUID,
        val adultsRu: UUID,
        val kidsRu: UUID,
        val english: UUID,
        val adultsEn: UUID,
        val kidsEn: UUID,
        val alone: UUID,
    )
}
