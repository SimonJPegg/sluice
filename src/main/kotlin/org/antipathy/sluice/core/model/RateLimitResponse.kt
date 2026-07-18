package org.antipathy.sluice.core.model

import kotlin.time.Duration

/** Closed set of outcomes. Forces exhaustive handling at the call site. */
sealed interface RateLimitResponse

/** Includes remaining budget so callers can back off before hitting the wall. */
data class Allowed(
    val remaining: UInt,
    val resetIn: Duration,
) : RateLimitResponse

/** Includes retry timing so callers know when to come back. */
data class Denied(
    val retryAfter: Duration,
) : RateLimitResponse

/** Internal errors as data, not thrown exceptions. */
data class Failed(
    val reason: String,
) : RateLimitResponse
