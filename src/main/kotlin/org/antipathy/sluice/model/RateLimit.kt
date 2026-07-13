package org.antipathy.sluice.model

import kotlin.time.Duration

/** Closed set of outcomes from a rate limit evaluation — forces exhaustive handling. */
sealed interface RateLimitResponse

/** Carries remaining budget so callers can make proactive decisions before hitting the limit. */
data class Allowed(
  val remaining: UInt,
  val resetIn: Duration,
): RateLimitResponse

/** Carries retry timing so callers know when to come back without guessing. */
data class Denied(
  val retryAfter: Duration,
): RateLimitResponse

/** Represents internal errors so they're part of the type system, not thrown exceptions. */
data class Failed(
  val reason: String,
): RateLimitResponse

/** Pairs a caller identity with a policy — the minimum input needed to make a decision. */
data class RateLimitRequest(
  val key: String,
  val policyID: String,
)
