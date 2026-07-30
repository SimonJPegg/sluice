package org.antipathy.sluice.core.store

import org.antipathy.sluice.core.model.Failed
import org.antipathy.sluice.core.model.FailedClosed
import org.antipathy.sluice.core.model.FailedOpen
import org.antipathy.sluice.core.model.FailureCategory
import org.antipathy.sluice.core.model.RateLimitResponse
import org.antipathy.sluice.core.policy.FailType
import org.antipathy.sluice.core.policy.Policy
import org.slf4j.LoggerFactory

/** Applies the failure mode dictated by policies to responses from its delegate */
class FailureModeCounterStore(private val delegate: CounterStore) : CounterStore {

  private val logger = LoggerFactory.getLogger(FailureModeCounterStore::class.java)

  fun getFailResult(
      result: Failed,
      policy: Policy,
  ): RateLimitResponse {
    return if (
        (result.failureCategory == FailureCategory.STORE_UNAVAILABLE) ||
            (result.failureCategory == FailureCategory.STORE_TIMEOUT) ||
            (result.failureCategory == FailureCategory.CIRCUIT_OPEN)
    ) {
      if (policy.failType == FailType.OPEN) {
        logger.error("Redis error, failing open as per {}", policy.id)
        FailedOpen(0u, policy.window)
      } else {
        logger.error("Redis error, failing closed as per {}", policy.id)
        FailedClosed(policy.window)
      }
    } else {
      result
    }
  }

  override suspend fun evaluate(key: String, policy: Policy): RateLimitResponse {
    return when (val result = delegate.evaluate(key, policy)) {
      is Failed -> getFailResult(result, policy)
      else -> result
    }
  }
}
