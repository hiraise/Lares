package app.familyhub.contract.validation

import app.familyhub.contract.api.FieldErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValidatorsTest {
    @Test
    fun login() {
        assertNull(Validators.login("alice"))
        assertNull(Validators.login("Alice.B-1_x"))
        assertEquals(FieldErrorCode.REQUIRED, Validators.login("  "))
        assertEquals(FieldErrorCode.TOO_SHORT, Validators.login("ab"))
        assertNull(Validators.login("a".repeat(32)))
        assertEquals(FieldErrorCode.TOO_LONG, Validators.login("a".repeat(33)))
        assertEquals(FieldErrorCode.INVALID_FORMAT, Validators.login("al ice"))
        assertEquals(FieldErrorCode.INVALID_FORMAT, Validators.login("алиса"))
        assertEquals("alice", Validators.normalizeLogin(" ALICE "))
    }

    @Test
    fun password() {
        assertNull(Validators.password("12345678"))
        assertEquals(FieldErrorCode.REQUIRED, Validators.password(""))
        assertEquals(FieldErrorCode.TOO_SHORT, Validators.password("1234567"))
        assertNull(Validators.password("x".repeat(128)))
        assertEquals(FieldErrorCode.TOO_LONG, Validators.password("x".repeat(129)))
    }

    @Test
    fun names() {
        assertNull(Validators.personName("Мама"))
        assertEquals(FieldErrorCode.REQUIRED, Validators.personName("   "))
        assertNull(Validators.personName(" " + "x".repeat(100) + " "))
        assertEquals(FieldErrorCode.TOO_LONG, Validators.personName("x".repeat(101)))
        assertNull(Validators.householdName("x".repeat(60)))
        assertEquals(FieldErrorCode.TOO_LONG, Validators.householdName("x".repeat(61)))
        assertEquals(FieldErrorCode.REQUIRED, Validators.householdName(""))
        assertNull(Validators.groupName("x".repeat(40)))
        assertEquals(FieldErrorCode.TOO_LONG, Validators.groupName("x".repeat(41)))
        assertEquals(FieldErrorCode.REQUIRED, Validators.deviceName(""))
        assertEquals(FieldErrorCode.TOO_LONG, Validators.platform("x".repeat(41)))
    }

    @Test
    fun timeZone() {
        assertNull(Validators.timeZone("Europe/Moscow"))
        assertNull(Validators.timeZone("UTC"))
        assertEquals(FieldErrorCode.INVALID_FORMAT, Validators.timeZone("Mars/Olympus"))
        assertEquals(FieldErrorCode.REQUIRED, Validators.timeZone(""))
    }

    @Test
    fun currencyAndLanguage() {
        assertNull(Validators.currency("RUB"))
        assertEquals(FieldErrorCode.INVALID_FORMAT, Validators.currency("rub"))
        assertEquals(FieldErrorCode.INVALID_FORMAT, Validators.currency("RUBL"))
        assertEquals(FieldErrorCode.REQUIRED, Validators.currency(""))
        assertNull(Validators.language("en"))
        assertNull(Validators.language("ru"))
        assertEquals(FieldErrorCode.INVALID_FORMAT, Validators.language("de"))
        assertEquals(FieldErrorCode.REQUIRED, Validators.language(""))
    }

    @Test
    fun birthDate() {
        fun check(
            year: Int?,
            month: Int?,
            day: Int?,
        ) = Validators.birthDate(year, month, day, currentYear = 2026)

        assertTrue(check(null, null, null).isEmpty())
        assertTrue(check(1980, 5, 31).isEmpty())
        assertTrue(check(null, 12, 25).isEmpty())
        assertTrue(check(1950, null, null).isEmpty())
        assertEquals(mapOf("birthYear" to FieldErrorCode.OUT_OF_RANGE), check(1899, 1, 1))
        assertEquals(mapOf("birthYear" to FieldErrorCode.OUT_OF_RANGE), check(2027, 1, 1))
        assertEquals(mapOf("birthMonth" to FieldErrorCode.OUT_OF_RANGE), check(2000, 13, 1))
        assertEquals(mapOf("birthMonth" to FieldErrorCode.OUT_OF_RANGE), check(2000, 0, null))
        assertEquals(mapOf("birthMonth" to FieldErrorCode.REQUIRED), check(2000, null, 5))
        assertEquals(mapOf("birthDay" to FieldErrorCode.OUT_OF_RANGE), check(2000, 4, 31))
        assertEquals(mapOf("birthDay" to FieldErrorCode.OUT_OF_RANGE), check(2000, 1, 0))
    }

    @Test
    fun february29() {
        assertTrue(Validators.birthDate(null, 2, 29, 2026).isEmpty(), "unknown year")
        assertTrue(Validators.birthDate(2024, 2, 29, 2026).isEmpty(), "leap year")
        assertTrue(Validators.birthDate(2000, 2, 29, 2026).isEmpty(), "400-year leap")
        assertEquals(mapOf("birthDay" to FieldErrorCode.OUT_OF_RANGE), Validators.birthDate(2023, 2, 29, 2026))
        assertEquals(mapOf("birthDay" to FieldErrorCode.OUT_OF_RANGE), Validators.birthDate(1900, 2, 29, 2026))
        assertEquals(mapOf("birthDay" to FieldErrorCode.OUT_OF_RANGE), Validators.birthDate(null, 2, 30, 2026))
    }

    @Test
    fun validationErrorsKeepFirstErrorPerField() {
        val errors =
            ValidationErrors()
                .add("login", null)
                .add("password", FieldErrorCode.TOO_SHORT)
                .add("password", FieldErrorCode.REQUIRED)
                .addAll(mapOf("birthDay" to FieldErrorCode.OUT_OF_RANGE))
        assertEquals(mapOf("password" to FieldErrorCode.TOO_SHORT, "birthDay" to FieldErrorCode.OUT_OF_RANGE), errors.fields)
        assertTrue(ValidationErrors().isEmpty)
    }
}
