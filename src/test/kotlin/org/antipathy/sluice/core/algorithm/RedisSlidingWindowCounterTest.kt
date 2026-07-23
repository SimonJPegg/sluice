package org.antipathy.sluice.core.algorithm

import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.antipathy.sluice.core.algorithm.redis.ScriptLoader
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Denied
import org.antipathy.sluice.core.policy.AlgorithmType
import org.antipathy.sluice.core.policy.FailType
import org.antipathy.sluice.core.policy.Policy
import org.antipathy.sluice.redis.RedisTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RedisSlidingWindowCounterTest : RedisTest() {

  private val defaultPolicy =
      Policy(
          id = "test-policy",
          limit = 10u,
          failType = FailType.OPEN,
          window = 3.seconds,
          algorithmType = AlgorithmType.SLIDING_WINDOW_COUNTER,
      )

  @Test
  fun `first request returns Allowed with remaining = limit - 1`() = runTest {
    val algorithm = RedisSlidingWindowCounter(ScriptLoader(connection))
    val key = "test-key"

    val result =
        Assertions.assertInstanceOf(Allowed::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(defaultPolicy.limit - 1u, result.remaining)
    assertEquals(defaultPolicy.window, result.resetIn)
  }

  @Test
  fun `multiple requests within same window - remaining decreases`() = runTest {
    val algorithm = RedisSlidingWindowCounter(ScriptLoader(connection))
    val key = "test-key"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals((defaultPolicy.limit - 1u - i.toUInt()), result.remaining)
    }
  }

  @Test
  fun `at limit - next request returns Denied`() = runTest {
    val algorithm = RedisSlidingWindowCounter(ScriptLoader(connection))
    val key = "test-key"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals((defaultPolicy.limit - 1u - i.toUInt()), result.remaining)
    }
    val result =
        Assertions.assertInstanceOf(Denied::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(defaultPolicy.window, result.retryAfter)
  }

  @Test
  fun `mid-window accuracy - 50 percent through window, previous count weighted at 50 percent`() =
      runBlocking {
        val algorithm = RedisSlidingWindowCounter(ScriptLoader(connection))
        val key = "test-key"
        repeat(defaultPolicy.limit.toInt()) { i ->
          val result = algorithm.calculate(key, defaultPolicy)
          check(result is Allowed)
          assertEquals((defaultPolicy.limit - 1u - i.toUInt()), result.remaining)
        }
        delay(defaultPolicy.window + (defaultPolicy.window / 2))
        val result =
            Assertions.assertInstanceOf(
                Allowed::class.java,
                algorithm.calculate(key, defaultPolicy),
            )
        assertTrue(result.remaining in 3u..6u)
      }

  @Test
  fun `window rolls over - previous count carries forward with weight`() = runBlocking {
    val algorithm = RedisSlidingWindowCounter(ScriptLoader(connection))
    val key = "test-key"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals((defaultPolicy.limit - 1u - i.toUInt()), result.remaining)
    }
    delay(defaultPolicy.window + 1.seconds)
    val result =
        Assertions.assertInstanceOf(Allowed::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(3u, result.remaining)
  }

  @Test
  fun `two windows stale - previous count discarded entirely, fresh start`() = runBlocking {
    val algorithm = RedisSlidingWindowCounter(ScriptLoader(connection))
    val key = "test-key"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals((defaultPolicy.limit - 1u - i.toUInt()), result.remaining)
    }
    delay((defaultPolicy.window * 3) + 10.seconds)
    val result =
        Assertions.assertInstanceOf(Allowed::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(defaultPolicy.limit - 1u, result.remaining)
  }

  @Test
  fun `burst at window boundary - previous window's weight prevents 2x burst`() = runBlocking {
    val algorithm = RedisSlidingWindowCounter(ScriptLoader(connection))
    val key = "test-key"
    val policy = defaultPolicy.copy(window = 5.seconds)
    // exhaust the budget at the start of the window
    repeat(policy.limit.toInt()) { algorithm.calculate(key, policy) }
    // wait past the boundary — need >1s into new window due to Redis integer-second TIME
    // granularity
    delay(7.seconds)
    // sliding window should prevent a full burst: fewer than limit requests allowed
    val results = List(policy.limit.toInt()) { algorithm.calculate(key, policy) }
    val allowed = results.count { it is Allowed }
    assertTrue(
        allowed < policy.limit.toInt(),
        "Expected burst protection: got $allowed allowed (fixed window would allow ${policy.limit})",
    )
    assertTrue(allowed > 0, "Expected at least one request to be allowed after boundary")
  }

  @Test
  fun `concurrent access - coroutines hammering same key, total allowed less than or equal limit`() =
      runBlocking {
        val algorithm = RedisSlidingWindowCounter(ScriptLoader(connection))
        val testKey = "test-key"
        val policy = defaultPolicy.copy(limit = 100u)
        withContext(Dispatchers.Default) {
          val threads = List(200) { async { algorithm.calculate(testKey, policy) } }
          val result = threads.awaitAll()
          val (allowed, denied) = result.partition { it is Allowed }
          assertEquals(
              100,
              allowed.size,
          )
          assertEquals(
              100,
              denied.size,
          )
        }
      }
}
