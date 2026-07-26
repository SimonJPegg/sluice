package org.antipathy.sluice.core.store

import io.lettuce.core.RedisException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
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
    private val connectionTimeout: Duration = 50.milliseconds,
) : CounterStore {

  private val logger = LoggerFactory.getLogger(RedisCounterStore::class.java)

  override suspend fun evaluate(key: String, policy: Policy): RateLimitResponse {
    return try {
      withTimeout(connectionTimeout) {
        algorithms.getValue(policy.algorithmType).calculate(key, policy)
      }
    } catch (_: NoSuchElementException) {
      Failed(reason = "Algorithm ${policy.algorithmType} has not been implemented yet", null)
    } catch (e: RedisException) {
      logger.error("Redis error", e)
      Failed("Redis failure ${e.message}", policy.window, FailureCategory.STORE_UNAVAILABLE)
    } catch (e: TimeoutCancellationException) {
      logger.error("timeout", e)
      Failed("Redis failure ${e.message}", policy.window, FailureCategory.STORE_TIMEOUT)
    }
  }
}
