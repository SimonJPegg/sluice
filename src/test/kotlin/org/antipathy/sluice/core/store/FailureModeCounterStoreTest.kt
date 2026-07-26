package org.antipathy.sluice.core.store

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.antipathy.sluice.core.algorithm.RedisFixedWindow
import org.antipathy.sluice.core.algorithm.redis.ScriptLoader
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Denied
import org.antipathy.sluice.core.policy.AlgorithmType
import org.antipathy.sluice.core.policy.FailType
import org.antipathy.sluice.core.policy.Policy
import org.antipathy.sluice.redis.RedisTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class FailureModeCounterStoreTest : RedisTest() {

  private val defaultPolicy =
      Policy(
          id = "test-policy",
          limit = 5u,
          failType = FailType.OPEN,
          window = 1.minutes,
          algorithmType = AlgorithmType.FIXED_WINDOW,
      )

  @Test
  fun `store fails open when the policy specifies it`() = runTest {
    val store =
        FailureModeCounterStore(
            RedisCounterStore(
                mapOf(AlgorithmType.FIXED_WINDOW to RedisFixedWindow(ScriptLoader(redisConnection)))
            )
        )
    val testKey = "test-key"
    redisConnection.close()
    val result = assertInstanceOf(Allowed::class.java, store.evaluate(testKey, defaultPolicy))

    assertEquals(0u, result.remaining)
    assertEquals(defaultPolicy.window, result.resetIn)
  }

  @Test
  fun `store fails closed when the policy specifies it`() = runTest {
    val store =
        FailureModeCounterStore(
            RedisCounterStore(
                mapOf(AlgorithmType.FIXED_WINDOW to RedisFixedWindow(ScriptLoader(redisConnection)))
            )
        )
    val testKey = "test-key"
    val policy = defaultPolicy.copy(failType = FailType.CLOSED)
    redisConnection.close()
    val result = assertInstanceOf(Denied::class.java, store.evaluate(testKey, policy))

    assertEquals(defaultPolicy.window, result.retryAfter)
  }

  @Test
  fun `store fails according to policy when redis redisConnection times out`() = runBlocking {
    val store =
        FailureModeCounterStore(
            RedisCounterStore(
                mapOf(
                    AlgorithmType.FIXED_WINDOW to RedisFixedWindow(ScriptLoader(redisConnection))
                ),
                1.milliseconds,
            )
        )
    val testKey = "test-key"
    val policy = defaultPolicy.copy(failType = FailType.CLOSED)
    redisConnection.close()
    val result = assertInstanceOf(Denied::class.java, store.evaluate(testKey, policy))

    assertEquals(defaultPolicy.window, result.retryAfter)
  }
}
