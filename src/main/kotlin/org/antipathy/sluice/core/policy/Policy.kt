package org.antipathy.sluice.core.policy

import kotlin.time.Duration
import kotlinx.serialization.Serializable
import org.antipathy.sluice.core.exceptions.InvalidPolicyConfigurationException

/** Consumers must declare what happens when Redis is down. No silent defaults. */
@Serializable(with = FailTypeSerializer::class)
enum class FailType {
  OPEN,
  CLOSED,
}

/** Algorithm choice lives in config, not code. */
@Serializable(with = AlgorithmTypeSerializer::class)
enum class AlgorithmType {
  FIXED_WINDOW,
  SLIDING_WINDOW_LOG,
  SLIDING_WINDOW_COUNTER,
  TOKEN_BUCKET,
}

/** Everything about how a rate limit behaves. Validated at construction, not at evaluation. */
@Serializable
data class Policy(
    val id: String,
    @Serializable(with = UIntSerializer::class) val limit: UInt,
    val failType: FailType,
    @Serializable(with = ISOFormatDurationSerializer::class) val window: Duration,
    val algorithmType: AlgorithmType,
) {

  fun validate(): Policy {
    if (id.isBlank()) throw InvalidPolicyConfigurationException("Policy id cannot be blank")
    if (window <= Duration.ZERO)
        throw InvalidPolicyConfigurationException("Policy window must be positive")
    return this
  }
}
