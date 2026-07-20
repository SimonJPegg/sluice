package org.antipathy.sluice.core.store

import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Denied
import org.antipathy.sluice.core.model.Failed
import org.antipathy.sluice.core.model.RateLimitResponse
import org.antipathy.sluice.core.policy.FailType
import org.antipathy.sluice.core.policy.Policy
import org.slf4j.LoggerFactory

/** Wraps a CounterStore and stops calling it when failures exceed a threshold.
 *
 * Resilience4j was an option, but this is a learning project, so ... */
class CircuitBreakerCounterStore(
    private val delegate: CounterStore,
    private val failureThreshold: Int,
    private val resetTimeout: Duration,
    private val clock: Clock = Clock.System,
) : CounterStore {

  private enum class State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  // Hold one atomic value rather than two
  private data class BreakerState(val failures: Int = 0, val lastFailure: Instant? = null) {

    fun resolve(failureThreshold: Int, resetTimeout: Duration, clock: Clock): State {
      if (failures < failureThreshold) return State.CLOSED
      val last = lastFailure ?: return State.CLOSED
      return if (clock.now() - last >= resetTimeout) State.HALF_OPEN else State.OPEN
    }
  }

  private val logger = LoggerFactory.getLogger(CircuitBreakerCounterStore::class.java)
  private val state: AtomicReference<BreakerState> = AtomicReference(BreakerState())

  override suspend fun evaluate(key: String, policy: Policy): RateLimitResponse {
    return when (state.get().resolve(failureThreshold, resetTimeout, clock)) {
      State.CLOSED -> callDelegate(key, policy)
      State.HALF_OPEN -> probe(key, policy)
      State.OPEN -> failFast(policy)
    }
  }

  private suspend fun callDelegate(key: String, policy: Policy): RateLimitResponse {
    val result = delegate.evaluate(key, policy)
    if (result is Failed) recordFailure() else reset()
    return result
  }

  private suspend fun probe(key: String, policy: Policy): RateLimitResponse {
    logger.debug("Circuit half-open, probing delegate")
    val result = delegate.evaluate(key, policy)
    if (result is Failed) {
      recordFailure()
    } else {
      logger.info("Probe succeeded, circuit closing")
      reset()
    }
    return result
  }

  private fun failFast(policy: Policy): RateLimitResponse {
    return if (policy.failType == FailType.OPEN) {
      // we can't calculate the values, so tell the caller to wait the remainder of this window
      Allowed(0u, policy.window)
    } else {
      Denied(policy.window)
    }
  }

  private fun recordFailure() {
    val updated =
        state.updateAndGet { it.copy(failures = it.failures + 1, lastFailure = clock.now()) }
    if (updated.failures == failureThreshold) {
      logger.error("Circuit breaker tripped after {} failures", updated.failures)
    }
  }

  private fun reset() {
    state.set(BreakerState())
  }
}
