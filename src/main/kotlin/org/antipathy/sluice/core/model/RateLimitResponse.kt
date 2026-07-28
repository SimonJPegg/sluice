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
    val retryAfter: Duration?,
    val failureCategory: FailureCategory = FailureCategory.SEE_REASON,
) : RateLimitResponse

/** Policy said allow when Redis is dead. Looks like Allowed but isn't. */
data class FailedOpen(
    val remaining: UInt,
    val resetIn: Duration,
) : RateLimitResponse

/** Policy said deny when Redis is dead. Looks like Denied but isn't. */
data class FailedClosed(
    val retryAfter: Duration,
) : RateLimitResponse

enum class FailureCategory {
  OVERLOADED, // we're load shedding
  CIRCUIT_OPEN, // circuit breaker has tripped
  STORE_UNAVAILABLE, // Huston, we have a problem
  STORE_TIMEOUT, // same, but smaller?
  SEE_REASON, // generic errors
}
