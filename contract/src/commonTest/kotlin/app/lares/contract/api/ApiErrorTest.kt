package app.lares.contract.api

import kotlin.test.Test
import kotlin.test.assertEquals

class ApiErrorTest {
    @Test
    fun errorBodyRoundTrips() {
        val body = ApiErrorBody(ApiError(ErrorCode.VALIDATION_FAILED, "Name must not be empty", mapOf("name" to FieldErrorCode.REQUIRED)))
        val json = LaresJson.encodeToString(body)
        assertEquals(
            """{"error":{"code":"VALIDATION_FAILED","message":"Name must not be empty","fields":{"name":"REQUIRED"}}}""",
            json,
        )
        assertEquals(body, LaresJson.decodeFromString<ApiErrorBody>(json))
    }

    @Test
    fun unknownErrorCodeDecodesToUnknown() {
        val body = LaresJson.decodeFromString<ApiErrorBody>("""{"error":{"code":"SOMETHING_NEW","message":"x"}}""")
        assertEquals(ErrorCode.UNKNOWN, body.error.code)
        assertEquals(emptyMap(), body.error.fields)
    }

    @Test
    fun unknownFieldErrorCodeDecodesToUnknown() {
        val body =
            LaresJson.decodeFromString<ApiErrorBody>(
                """{"error":{"code":"VALIDATION_FAILED","message":"x","fields":{"name":"SOMETHING_NEW"}}}""",
            )
        assertEquals(mapOf("name" to FieldErrorCode.UNKNOWN), body.error.fields)
    }
}
