package app.lares.contract.api

/** Client–server protocol version (ADR-0008). Every `/api/v1` request carries [HEADER]. */
object Protocol {
    const val VERSION = 1
    const val MIN_SUPPORTED = 1
    const val HEADER = "X-Lares-Protocol"
}
