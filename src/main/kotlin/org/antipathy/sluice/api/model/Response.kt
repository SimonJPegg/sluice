package org.antipathy.sluice.api.model

import kotlinx.serialization.Serializable

sealed interface Response

/** Consistent JSON error body shape for all error responses. */
@Serializable data class ErrorResponse(val error: String) : Response
