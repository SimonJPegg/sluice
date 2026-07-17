package org.antipathy.sluice.core.algorithm

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Denied
import org.antipathy.sluice.core.policy.AlgorithmType
import org.antipathy.sluice.core.policy.FailType
import org.antipathy.sluice.core.policy.Policy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class InMemoryTokenBucketTest {

  private val defaultPolicy =
      Policy(
          id = "test-policy",
          limit = 60u,
          failType = FailType.OPEN,
          window = 1.minutes,
          algorithmType = AlgorithmType.TOKEN_BUCKET,
      )

  @Test
  fun `first request returns Allowed with remaining = limit - 1`() = runTest {
    val clock = FakeClock()
    val algorithm = InMemoryTokenBucket(clock)
    val key = "test-key"

    val result = assertInstanceOf(Allowed::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(defaultPolicy.limit - 1u, result.remaining)
    assertEquals(
        (defaultPolicy.window.inWholeSeconds.toInt() / defaultPolicy.limit.toInt()).seconds,
        result.resetIn)
  }

  @Test
  fun `burst capacity - exhaust full bucket, next request denied`() = runTest {
    val clock = FakeClock()
    val algorithm = InMemoryTokenBucket(clock)
    val key = "test-key"

    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals(defaultPolicy.limit - (i + 1).toUInt(), result.remaining)
    }
    val result = assertInstanceOf(Denied::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(
        (defaultPolicy.window.inWholeSeconds.toInt() / defaultPolicy.limit.toInt()).seconds,
        result.retryAfter)
  }

  @Test
  fun `steady state refill - after waiting one token period, one more request allowed`() = runTest {
    val clock = FakeClock()
    val algorithm = InMemoryTokenBucket(clock)
    val key = "test-key"

    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals(defaultPolicy.limit - (i + 1).toUInt(), result.remaining)
    }
    val deny = assertInstanceOf(Denied::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(
        (defaultPolicy.window.inWholeSeconds.toInt() / defaultPolicy.limit.toInt()).seconds,
        deny.retryAfter)
    clock.advance(1.seconds)
    val result = assertInstanceOf(Allowed::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(0u, result.remaining)
    assertEquals(defaultPolicy.window, result.resetIn)
  }

  @Test
  fun `overflow cap - long idle does not exceed limit`() = runTest {
    val clock = FakeClock()
    val algorithm = InMemoryTokenBucket(clock)
    val key = "test-key"

    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals(defaultPolicy.limit - (i + 1).toUInt(), result.remaining)
    }
    assertInstanceOf(Denied::class.java, algorithm.calculate(key, defaultPolicy))
    clock.advance(5.minutes)
    val result = assertInstanceOf(Allowed::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(defaultPolicy.limit - 1u, result.remaining)
    assertEquals(1.seconds, result.resetIn)
  }

  @Test
  fun `empty bucket - retryAfter is time until next token`() = runTest {
    val clock = FakeClock()
    val algorithm = InMemoryTokenBucket(clock)
    val key = "test-key"

    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals(defaultPolicy.limit - (i + 1).toUInt(), result.remaining)
    }
    val result = assertInstanceOf(Denied::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(1.seconds, result.retryAfter)
  }

  @Test
  fun `partial refill - half a token period gives no new requests`() = runTest {
    val clock = FakeClock()
    val algorithm = InMemoryTokenBucket(clock)
    val key = "test-key"

    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals(defaultPolicy.limit - (i + 1).toUInt(), result.remaining)
    }
    clock.advance(0.5.seconds)
    val result = assertInstanceOf(Denied::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(0.5.seconds, result.retryAfter)
  }

  @Test
  fun `concurrent access - coroutines hammering same key, total allowed equals limit`() =
      runBlocking {
        val clock = FakeClock()
        val algorithm = InMemoryTokenBucket(clock)
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
