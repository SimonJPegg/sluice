package org.antipathy.sluice.core.store

import org.antipathy.sluice.core.algorithm.Algorithm
import org.antipathy.sluice.core.model.AlgorithmType
import org.antipathy.sluice.core.model.Failed
import org.antipathy.sluice.core.model.Policy
import org.antipathy.sluice.core.model.RateLimitResponse

/** For tests and single-node deployments where Redis is overkill. */
class InMemoryCounterStore(
    private val algorithms: Map<AlgorithmType, Algorithm>,
) : CounterStore {

  override suspend fun evaluate(key: String, policy: Policy): RateLimitResponse {
    return try {
      algorithms.getValue(policy.algorithmType).calculate(key, policy)
    } catch (_: NoSuchElementException) {
      Failed(reason = "Algorithm ${policy.algorithmType} has not been implemented yet")
    }
  }
}
