package org.antipathy.sluice.core.store

import io.lettuce.core.RedisException
import org.antipathy.sluice.core.algorithm.RedisAlgorithm
import org.antipathy.sluice.core.model.AlgorithmType
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Denied
import org.antipathy.sluice.core.model.FailType
import org.antipathy.sluice.core.model.Failed
import org.antipathy.sluice.core.model.Policy
import org.antipathy.sluice.core.model.RateLimitResponse

/** Dispatches to Redis-backed algorithms. Handles connection failures per the policy's fail stance. */
class RedisCounterStore(private val algorithms: Map<AlgorithmType, RedisAlgorithm>) : CounterStore {


  override suspend fun evaluate(key: String, policy: Policy): RateLimitResponse {
    return try {
      algorithms.getValue(policy.algorithmType).calculate(key, policy)
    } catch (_: NoSuchElementException) {
      Failed(reason = "Algorithm ${policy.algorithmType} has not been implemented yet")
    } catch (_: RedisException) {
      return if (policy.failType == FailType.OPEN) {
        // no way to calculate here, so tell the consumer that they've hit the limit for now and to
        // wait until the
        // next window. Policy has already told us to allow it.
        Allowed(0u, policy.window)
      } else {
        Denied(policy.window)
      }
    }
  }
}
