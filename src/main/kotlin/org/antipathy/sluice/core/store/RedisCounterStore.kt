package org.antipathy.sluice.core.store

import io.lettuce.core.RedisException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.antipathy.sluice.core.algorithm.RedisAlgorithm
import org.antipathy.sluice.core.model.AlgorithmType
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Denied
import org.antipathy.sluice.core.model.FailType
import org.antipathy.sluice.core.model.Failed
import org.antipathy.sluice.core.model.Policy
import org.antipathy.sluice.core.model.RateLimitResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Dispatches to Redis-backed algorithms. Handles connection failures per the policy's fail stance.
 */
class RedisCounterStore(
  private val algorithms: Map<AlgorithmType, RedisAlgorithm>,
  private val connectionTimeout: Duration = 50.milliseconds
) : CounterStore {

  override suspend fun evaluate(key: String, policy: Policy): RateLimitResponse {
    return try {
      withTimeout(connectionTimeout) {
        algorithms.getValue(policy.algorithmType).calculate(key, policy)
      }
    } catch (_: NoSuchElementException) {
      Failed(reason = "Algorithm ${policy.algorithmType} has not been implemented yet")
    } catch (e: Exception) {
      return when {
        e is RedisException || e is TimeoutCancellationException -> {
          if (policy.failType == FailType.OPEN) {
            // can't calculate, fail-open per policy
            Allowed(0u, policy.window)
          } else {
            Denied(policy.window)
          }
        }
        else -> throw e
      }
    }
  }
}
