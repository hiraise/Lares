package app.familyhub.client.ui.common

import androidx.compose.runtime.Composable
import app.familyhub.client.resources.Res
import app.familyhub.client.resources.error_builtin_group
import app.familyhub.client.resources.error_conflict
import app.familyhub.client.resources.error_entity_deleted
import app.familyhub.client.resources.error_entity_not_found
import app.familyhub.client.resources.error_forbidden
import app.familyhub.client.resources.error_internal
import app.familyhub.client.resources.error_invalid_credentials
import app.familyhub.client.resources.error_invite_invalid
import app.familyhub.client.resources.error_last_admin
import app.familyhub.client.resources.error_login_code_invalid
import app.familyhub.client.resources.error_login_taken
import app.familyhub.client.resources.error_mutation_expired
import app.familyhub.client.resources.error_network
import app.familyhub.client.resources.error_not_familyhub
import app.familyhub.client.resources.error_not_familyhub_qr
import app.familyhub.client.resources.error_not_found
import app.familyhub.client.resources.error_person_is_member
import app.familyhub.client.resources.error_protocol_unsupported
import app.familyhub.client.resources.error_rate_limited
import app.familyhub.client.resources.error_session_revoked
import app.familyhub.client.resources.error_setup_already_done
import app.familyhub.client.resources.error_setup_code_invalid
import app.familyhub.client.resources.error_tag_name_taken
import app.familyhub.client.resources.error_unauthenticated
import app.familyhub.client.resources.error_unknown
import app.familyhub.client.resources.error_validation_failed
import app.familyhub.client.resources.field_invalid_format
import app.familyhub.client.resources.field_out_of_range
import app.familyhub.client.resources.field_required
import app.familyhub.client.resources.field_taken
import app.familyhub.client.resources.field_too_long
import app.familyhub.client.resources.field_too_short
import app.familyhub.client.resources.field_unknown
import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.FieldErrorCode
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// The one place where error codes become user-visible text.

fun ErrorCode.messageRes(): StringResource =
    when (this) {
        ErrorCode.VALIDATION_FAILED -> Res.string.error_validation_failed
        ErrorCode.PROTOCOL_UNSUPPORTED -> Res.string.error_protocol_unsupported
        ErrorCode.UNAUTHENTICATED, ErrorCode.SESSION_EXPIRED -> Res.string.error_unauthenticated
        ErrorCode.SESSION_REVOKED -> Res.string.error_session_revoked
        ErrorCode.INVALID_CREDENTIALS -> Res.string.error_invalid_credentials
        ErrorCode.FORBIDDEN -> Res.string.error_forbidden
        ErrorCode.NOT_FOUND -> Res.string.error_not_found
        ErrorCode.LOGIN_TAKEN -> Res.string.error_login_taken
        ErrorCode.CONFLICT -> Res.string.error_conflict
        ErrorCode.LAST_ADMIN -> Res.string.error_last_admin
        ErrorCode.BUILTIN_GROUP -> Res.string.error_builtin_group
        ErrorCode.SETUP_ALREADY_DONE -> Res.string.error_setup_already_done
        ErrorCode.SETUP_CODE_INVALID -> Res.string.error_setup_code_invalid
        ErrorCode.INVITE_INVALID -> Res.string.error_invite_invalid
        ErrorCode.LOGIN_CODE_INVALID -> Res.string.error_login_code_invalid
        ErrorCode.RATE_LIMITED -> Res.string.error_rate_limited
        ErrorCode.INTERNAL -> Res.string.error_internal
        ErrorCode.ENTITY_NOT_FOUND -> Res.string.error_entity_not_found
        ErrorCode.ENTITY_DELETED -> Res.string.error_entity_deleted
        ErrorCode.MUTATION_EXPIRED -> Res.string.error_mutation_expired
        ErrorCode.TAG_NAME_TAKEN -> Res.string.error_tag_name_taken
        ErrorCode.PERSON_IS_MEMBER -> Res.string.error_person_is_member
        ErrorCode.UNKNOWN -> Res.string.error_unknown
    }

fun UiError.messageRes(): StringResource =
    when (this) {
        is UiError.Api -> code.messageRes()
        UiError.Network -> Res.string.error_network
        UiError.NotFamilyHub -> Res.string.error_not_familyhub
        UiError.NotFamilyHubQr -> Res.string.error_not_familyhub_qr
    }

fun FieldErrorCode.messageRes(): StringResource =
    when (this) {
        FieldErrorCode.REQUIRED -> Res.string.field_required
        FieldErrorCode.TOO_SHORT -> Res.string.field_too_short
        FieldErrorCode.TOO_LONG -> Res.string.field_too_long
        FieldErrorCode.INVALID_FORMAT -> Res.string.field_invalid_format
        FieldErrorCode.OUT_OF_RANGE -> Res.string.field_out_of_range
        FieldErrorCode.TAKEN -> Res.string.field_taken
        FieldErrorCode.UNKNOWN -> Res.string.field_unknown
    }

@Composable
fun UiError.message(): String = stringResource(messageRes())

@Composable
fun FieldError?.text(): String? = this?.let { stringResource(it.res) }

/** A field error as shown under an input: a field code, or a client-only message such as "passwords differ". */
data class FieldError(
    val res: StringResource,
) {
    companion object {
        fun of(code: FieldErrorCode?): FieldError? = code?.let { FieldError(it.messageRes()) }
    }
}
