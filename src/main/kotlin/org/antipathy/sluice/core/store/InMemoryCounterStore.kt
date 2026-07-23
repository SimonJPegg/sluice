package org.antipathy.sluice.core.store

import org.antipathy.sluice.core.algorithm.InMemoryAlgorithm
import org.antipathy.sluice.core.model.Failed
import org.antipathy.sluice.core.model.RateLimitResponse
import org.antipathy.sluice.core.policy.AlgorithmType
import org.antipathy.sluice.core.policy.Policy
import org.slf4j.LoggerFactory

/** For tests and single-node deployments where Redis is overkill. */
class InMemoryCounterStore(
    private val algorithms: Map<AlgorithmType, InMemoryAlgorithm>,
) : CounterStore {

  private val logger = LoggerFactory.getLogger(InMemoryCounterStore::class.java)

  override suspend fun evaluate(key: String, policy: Policy): RateLimitResponse {
    return try {
      algorithms.getValue(policy.algorithmType).calculate(key, policy)
    } catch (_: NoSuchElementException) {
      val error = "Algorithm ${policy.algorithmType} has not been implemented yet"
      logger.error("Request failed: $error")
      Failed(reason = error, null)
    }
  }
}
