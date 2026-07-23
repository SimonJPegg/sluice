package org.antipathy.sluice.core.algorithm

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.antipathy.sluice.core.algorithm.redis.ScriptLoader
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Denied
import org.antipathy.sluice.core.policy.AlgorithmType
import org.antipathy.sluice.core.policy.FailType
import org.antipathy.sluice.core.policy.Policy
import org.antipathy.sluice.redis.RedisTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RedisTokenBucketTest : RedisTest() {
  private val defaultPolicy =
      Policy(
          id = "test-policy",
          limit = 3u,
          failType = FailType.OPEN,
          window = 3.seconds,
          algorithmType = AlgorithmType.TOKEN_BUCKET,
      )

  // TokenBucket returns subsecond accuracy, these tests are not entirely deterministic
  private val tolerance = 200.milliseconds

  private fun assertDurationApprox(expected: Duration, actual: Duration, msg: String = "") {
    assertTrue(
        actual >= expected - tolerance && actual <= expected + tolerance,
        "$msg expected ~$expected but was $actual",
    )
  }

  @Test
  fun `first request returns Allowed with remaining = limit - 1`() = runBlocking {
    val algorithm = RedisTokenBucket(ScriptLoader(connection))
    val key = "test-key"

    val result = assertInstanceOf(Allowed::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(2u, result.remaining)
    assertDurationApprox(1.seconds, result.resetIn)
  }

  @Test
  fun `burst capacity - exhaust full bucket, next request denied`() = runBlocking {
    val algorithm = RedisTokenBucket(ScriptLoader(connection))
    val key = "test-key"

    repeat(defaultPolicy.limit.toInt()) {
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
    }
    val result = assertInstanceOf(Denied::class.java, algorithm.calculate(key, defaultPolicy))
    assertDurationApprox(1.seconds, result.retryAfter)
  }

  @Test
  fun `steady state refill - after waiting one token period, one more request allowed`() =
      runBlocking {
        val algorithm = RedisTokenBucket(ScriptLoader(connection))
        val key = "test-key"

        repeat(defaultPolicy.limit.toInt()) {
          val result = algorithm.calculate(key, defaultPolicy)
          check(result is Allowed)
        }
        assertInstanceOf(Denied::class.java, algorithm.calculate(key, defaultPolicy))
        delay(1.1.seconds)
        val result = assertInstanceOf(Allowed::class.java, algorithm.calculate(key, defaultPolicy))
        assertEquals(0u, result.remaining)
        assertDurationApprox(3.seconds, result.resetIn)
      }

  @Test
  fun `overflow cap - long idle does not exceed limit`() = runBlocking {
    val algorithm = RedisTokenBucket(ScriptLoader(connection))
    val key = "test-key"

    repeat(defaultPolicy.limit.toInt()) {
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
    }
    assertInstanceOf(Denied::class.java, algorithm.calculate(key, defaultPolicy))
    delay(3.1.seconds)
    val result = assertInstanceOf(Allowed::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(2u, result.remaining)
    assertDurationApprox(1.seconds, result.resetIn)
  }

  @Test
  fun `empty bucket - retryAfter is time until next token`() = runBlocking {
    val algorithm = RedisTokenBucket(ScriptLoader(connection))
    val key = "test-key"

    repeat(defaultPolicy.limit.toInt()) {
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
    }
    val result = assertInstanceOf(Denied::class.java, algorithm.calculate(key, defaultPolicy))
    assertDurationApprox(1.seconds, result.retryAfter)
  }

  @Test
  fun `partial refill - half a token period gives no new requests`() = runBlocking {
    val algorithm = RedisTokenBucket(ScriptLoader(connection))
    val key = "test-key"

    repeat(defaultPolicy.limit.toInt()) {
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
    }
    delay(0.5.seconds)
    val result = assertInstanceOf(Denied::class.java, algorithm.calculate(key, defaultPolicy))
    assertTrue(result.retryAfter > 0.seconds, "retryAfter should be positive")
    assertTrue(result.retryAfter < 1.seconds, "retryAfter should be less than one token period")
  }

  @Test
  fun `concurrent access - coroutines hammering same key, total allowed equals limit`() =
      runBlocking {
        val algorithm = RedisTokenBucket(ScriptLoader(connection))
        val key = "test-key"
        val policy = defaultPolicy.copy(limit = 100u)
        withContext(Dispatchers.Default) {
          val threads = List(200) { async { algorithm.calculate(key, policy) } }
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
