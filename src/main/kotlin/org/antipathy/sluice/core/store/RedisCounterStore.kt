package org.antipathy.sluice.core.store

import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisException
import org.antipathy.sluice.core.algorithm.RedisAlgorithm
import org.antipathy.sluice.core.model.Failed
import org.antipathy.sluice.core.model.FailureCategory
import org.antipathy.sluice.core.model.RateLimitResponse
import org.antipathy.sluice.core.policy.AlgorithmType
import org.antipathy.sluice.core.policy.Policy
import org.slf4j.LoggerFactory

/**
 * Dispatches to Redis-backed algorithms. Handles connection failures per the policy's fail stance.
 */
class RedisCounterStore(
    private val algorithms: Map<AlgorithmType, RedisAlgorithm>,
) : CounterStore {

  private val logger = LoggerFactory.getLogger(RedisCounterStore::class.java)

  override suspend fun evaluate(key: String, policy: Policy): RateLimitResponse {
    return try {
      algorithms.getValue(policy.algorithmType).calculate("${policy.id}:${key}", policy)
    } catch (e: RedisCommandTimeoutException) {
      logger.error("timeout", e)
      Failed("Redis failure ${e.message}", policy.window, FailureCategory.STORE_TIMEOUT)
    } catch (_: NoSuchElementException) {
      Failed(reason = "Algorithm ${policy.algorithmType} has not been implemented yet", null)
    } catch (e: RedisException) {
      logger.error("Redis error", e)
      Failed("Redis failure ${e.message}", policy.window, FailureCategory.STORE_UNAVAILABLE)
    }
  }
}
