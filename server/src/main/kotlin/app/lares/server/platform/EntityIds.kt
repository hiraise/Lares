package app.lares.server.platform

import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.server.platform.http.ApiException
import java.util.UUID

/** A new UUIDv7 for a server-created entity (conventions: ids come from `Ids.newId()`). */
fun newEntityId(): UUID = UUID.fromString(Ids.newId())

/**
 * Parses an id from a request path or body. Malformed ids answer `NOT_FOUND`, like ids of other households:
 * a caller learns nothing about which ids exist (architecture/api.md § Conventions).
 */
fun parseIdOrNotFound(
    text: String?,
    what: String,
): UUID = text?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw notFound(what)

fun notFound(what: String) = ApiException(ErrorCode.NOT_FOUND, "$what not found")
