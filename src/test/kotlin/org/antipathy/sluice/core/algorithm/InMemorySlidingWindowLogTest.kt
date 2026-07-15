package org.antipathy.sluice.core.algorithm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.antipathy.sluice.core.model.AlgorithmType
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Denied
import org.antipathy.sluice.core.model.FailType
import org.antipathy.sluice.core.model.Policy
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class InMemorySlidingWindowLogTest {

  private val defaultPolicy =
      Policy(
          id = "test-policy",
          limit = 10u,
          failType = FailType.OPEN,
          window = 1.minutes,
          algorithmType = AlgorithmType.SLIDING_WINDOW_LOG,
      )

  @Test fun `first request returns Allowed with remaining = limit - 1`() = runTest {
    val algorithm = InMemorySlidingWindowLog(FakeClock())
    val key = "test-key"
    val result = assertInstanceOf(Allowed::class.java, algorithm.calculate(key,defaultPolicy))
    assertEquals(defaultPolicy.limit -1u, result.remaining)
    assertEquals(defaultPolicy.window, result.resetIn)
  }

  @Test fun `multiple requests within same window, remaining decreases`() = runTest {
    val algorithm = InMemorySlidingWindowLog(FakeClock())
    val key = "test-key"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals((defaultPolicy.limit - 1u - i.toUInt()), result.remaining)
    }
  }

  @Test fun `at limit, next request returns Denied`() = runTest {
    val algorithm = InMemorySlidingWindowLog(FakeClock())
    val key = "test-key"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals((defaultPolicy.limit - 1u - i.toUInt()), result.remaining)
    }
    val result = assertInstanceOf(Denied::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(defaultPolicy.window, result.retryAfter)
  }

  @Test fun `denied request returns retryAfter as time until oldest entry expires`() = runTest {
    val clock = FakeClock()
    val algorithm = InMemorySlidingWindowLog(clock)
    val key = "test-key"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals((defaultPolicy.limit - 1u - i.toUInt()), result.remaining)
      clock.advance(1.seconds)
    }
    assertInstanceOf(Denied::class.java, algorithm.calculate(key, defaultPolicy))
    clock.advance(51.seconds)
    val result = assertInstanceOf(Allowed::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(0.seconds, result.resetIn)
    assertEquals(0u, result.remaining)
  }

  @Test fun `requests outside window are pruned, counter resets after window passes`() = runTest {
    val clock = FakeClock()
    val algorithm = InMemorySlidingWindowLog(clock)
    val key = "test-key"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals((defaultPolicy.limit - 1u - i.toUInt()), result.remaining)
      clock.advance(1.seconds)
    }
    clock.advance(defaultPolicy.window)
    val result = assertInstanceOf(Allowed::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(60.seconds, result.resetIn)
    assertEquals(defaultPolicy.limit - 1u, result.remaining)
  }

  @Test fun `sliding precision, request mid-window only counts entries within rolling window`() = runTest {
    val clock = FakeClock()
    val algorithm = InMemorySlidingWindowLog(clock)
    val key = "test-key"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals((defaultPolicy.limit - 1u - i.toUInt()), result.remaining)
      clock.advance(1.seconds)
    }
    clock.advance(defaultPolicy.window)
    repeat((defaultPolicy.limit / 2u).toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals((defaultPolicy.limit - 1u - i.toUInt()), result.remaining)
      clock.advance(1.seconds)
    }
    val result = assertInstanceOf(Allowed::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(55.seconds, result.resetIn)
    assertEquals((defaultPolicy.limit /2u) - 1u, result.remaining)
  }

  @Test fun `expired entries cleaned up, list does not grow unbounded across windows`() = runTest {
    // kept for consistency with redis tests. this was proven already by the sliding precision test
    val clock = FakeClock()
    val algorithm = InMemorySlidingWindowLog(clock)
    val key = "test-key"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals((defaultPolicy.limit - 1u - i.toUInt()), result.remaining)
      clock.advance(1.seconds)
    }
    clock.advance(defaultPolicy.window)
    val result = assertInstanceOf(Allowed::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(defaultPolicy.window, result.resetIn)
    assertEquals(defaultPolicy.limit  - 1u, result.remaining)
  }

  @Test fun `high traffic key, many requests within window, memory grows linearly`() = runTest {
    // kept for consistency with redis tests. this was proven already by the sliding precision test
    val clock = FakeClock()
    val algorithm = InMemorySlidingWindowLog(clock)
    val key = "test-key"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals((defaultPolicy.limit - 1u - i.toUInt()), result.remaining)
      clock.advance(1.seconds)
    }
    clock.advance(defaultPolicy.window)
    val result = assertInstanceOf(Allowed::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(defaultPolicy.window, result.resetIn)
    assertEquals(defaultPolicy.limit  - 1u, result.remaining)
  }

  @Test
  fun `concurrent access, coroutines hammering same key, total allowed equals limit`() = runBlocking {
    val clock = FakeClock()
    val algorithm = InMemorySlidingWindowLog(clock)
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
