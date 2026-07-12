package org.antipathy.sluice.model

import kotlin.time.Duration
import org.antipathy.sluice.exceptions.InvalidPolicyConfigurationException

/** Forces consumers to declare their failure stance — no implicit default. */
enum class FailType {
  OPEN, CLOSED
}

/** Selector for the counting strategy — keeps algorithm choice as configuration, not code. */
enum class AlgorithmType {
  FIXED_WINDOW, SLIDING_WINDOW_LOG, SLIDING_WINDOW_COUNTER, TOKEN_BUCKET
}

/** Single source of truth for how a rate limit behaves — validated at construction time. */
data class Policy(
  val id: String,
  val limit: UInt,
  val failType: FailType,
  val window: Duration,
  val algorithmType: AlgorithmType
) {
  init {
      if(id.isBlank()) throw InvalidPolicyConfigurationException( "Policy id cannot be blank" )
      if(window <= Duration.ZERO) throw InvalidPolicyConfigurationException( "Policy window must be positive" )

  }
}
