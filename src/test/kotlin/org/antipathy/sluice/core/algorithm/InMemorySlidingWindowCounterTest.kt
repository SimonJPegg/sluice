package org.antipathy.sluice.core.algorithm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.antipathy.sluice.core.model.AlgorithmType
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Denied
import org.antipathy.sluice.core.model.FailType
import org.antipathy.sluice.core.model.Policy
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class InMemorySlidingWindowCounterTest {

  private val defaultPolicy =
      Policy(
          id = "test-policy",
          limit = 10u,
          failType = FailType.OPEN,
          window = 1.minutes,
          algorithmType = AlgorithmType.SLIDING_WINDOW_COUNTER,
      )

  @Test fun `first request returns Allowed with remaining = limit - 1`() = runTest {
    val algorithm = InMemorySlidingWindowCounter(FakeClock())
    val key = "test-key"

    val result = Assertions.assertInstanceOf(Allowed::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(defaultPolicy.limit - 1u, result.remaining)
    assertEquals(defaultPolicy.window, result.resetIn)
  }

  @Test fun `multiple requests within same window - remaining decreases`() = runTest {
    val algorithm = InMemorySlidingWindowCounter(FakeClock())
    val key = "test-key"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals((defaultPolicy.limit - 1u - i.toUInt()), result.remaining)
    }
  }

  @Test fun `at limit, next request returns Denied`() = runTest {
    val algorithm = InMemorySlidingWindowCounter(FakeClock())
    val key = "test-key"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals((defaultPolicy.limit - 1u - i.toUInt()), result.remaining)
    }
    val result = Assertions.assertInstanceOf(Denied::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(defaultPolicy.window, result.retryAfter)
  }

  @Test
  fun `mid-window accuracy, 50 percent through window, previous count weighted at 50 percent`() = runTest {
    val clock = FakeClock()
    val algorithm = InMemorySlidingWindowCounter(clock)
    val key = "test-key"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals((defaultPolicy.limit  - 1u - i.toUInt()), result.remaining)
    }
    clock.advance(defaultPolicy.window + (defaultPolicy.window / 2))
    val result = Assertions.assertInstanceOf(Allowed::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals((defaultPolicy.limit / 2u) - 1u, result.remaining)
  }

  @Test fun `window rolls over, previous count carries forward with weight`() = runTest {
    val clock = FakeClock()
    val algorithm = InMemorySlidingWindowCounter(clock)
    val key = "test-key"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals((defaultPolicy.limit  - 1u - i.toUInt()), result.remaining)
    }
    clock.advance(defaultPolicy.window + 10.seconds)
    val result = Assertions.assertInstanceOf(Allowed::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(1u, result.remaining)
  }

  @Test fun `two windows stale, previous count discarded entirely, fresh start`() = runTest {
    val clock = FakeClock()
    val algorithm = InMemorySlidingWindowCounter(clock)
    val key = "test-key"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals((defaultPolicy.limit  - 1u - i.toUInt()), result.remaining)
    }
    clock.advance((defaultPolicy.window * 3) + 10.seconds)
    val result = Assertions.assertInstanceOf(Allowed::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(defaultPolicy.limit -1u, result.remaining)
  }

  @Test fun `burst at window boundary, previous window's weight prevents 2x burst`() = runTest {
    val clock = FakeClock()
    val algorithm = InMemorySlidingWindowCounter(clock)
    val key = "test-key"
    // start the clock
    algorithm.calculate(key, defaultPolicy)
    clock.advance(59.seconds)
    // use up the rest of the budget
    repeat((defaultPolicy.limit -1u).toInt()) { i ->
      val result = algorithm.calculate(key, defaultPolicy)
      check(result is Allowed)
      assertEquals((defaultPolicy.limit  - 2u - i.toUInt()), result.remaining)
    }
    clock.advance(2.seconds)
    //first request passes
    Assertions.assertInstanceOf(Allowed::class.java, algorithm.calculate(key, defaultPolicy))
    // but used up the budget
    val result = Assertions.assertInstanceOf(Denied::class.java, algorithm.calculate(key, defaultPolicy))
    assertEquals(59.seconds, result.retryAfter)

  }

  @Test
  fun `concurrent access, coroutines hammering same key, total allowed less than or equal limit`() =
    runBlocking {
      val algorithm = InMemorySlidingWindowCounter()
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
