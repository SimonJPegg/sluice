package org.antipathy.sluice.core.model

import kotlin.time.Duration
import org.antipathy.sluice.core.exceptions.InvalidPolicyConfigurationException

/** Consumers must declare what happens when Redis is down. No silent defaults. */
enum class FailType {
  OPEN,
  CLOSED
}

/** Algorithm choice lives in config, not code. */
enum class AlgorithmType {
  FIXED_WINDOW,
  SLIDING_WINDOW_LOG,
  SLIDING_WINDOW_COUNTER,
  TOKEN_BUCKET
}

/** Everything about how a rate limit behaves. Validated at construction, not at evaluation. */
data class Policy(
    val id: String,
    val limit: UInt,
    val failType: FailType,
    val window: Duration,
    val algorithmType: AlgorithmType
) {
  init {
    if (id.isBlank()) throw InvalidPolicyConfigurationException("Policy id cannot be blank")
    if (window <= Duration.ZERO)
        throw InvalidPolicyConfigurationException("Policy window must be positive")
  }
}
