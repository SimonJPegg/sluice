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

class FixedWindowCounterTest {
  private val defaultPolicy =
      Policy(
          id = "test-policy",
          limit = 5u,
          failType = FailType.OPEN,
          window = 1.minutes,
          algorithmType = AlgorithmType.FIXED_WINDOW,
      )

  @Test
  fun `first request returns Allowed with remaining = limit - 1`() = runTest {
    val algorithm = InMemoryFixedWindow(clock = FakeClock())
    val testKey = "test-key"
    val result = assertInstanceOf(Allowed::class.java, algorithm.calculate(testKey, defaultPolicy))

    assertEquals(defaultPolicy.limit - 1u, result.remaining)
    assertEquals(defaultPolicy.window, result.resetIn)
  }

  @Test
  fun `multiple increments,  remaining decreases with each request`() = runTest {
    val algorithm = InMemoryFixedWindow(clock = FakeClock())
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate("key", defaultPolicy)
      check(result is Allowed)
      assertEquals((4 - i).toUInt(), result.remaining)
    }
  }

  @Test
  fun `at-limit, request number limit+1 returns Denied`() = runTest {
    val algorithm = InMemoryFixedWindow(clock = FakeClock())
    val testKey = "test-key"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(testKey, defaultPolicy)
      check(result is Allowed)
      assertEquals((4 - i).toUInt(), result.remaining)
    }
    val result = assertInstanceOf(Denied::class.java, algorithm.calculate(testKey, defaultPolicy))
    assertEquals(defaultPolicy.window, result.retryAfter)
  }

  @Test
  fun `window expiry, after advancing clock past window, counter resets`() = runTest {
    val clock = FakeClock()
    val algorithm = InMemoryFixedWindow(clock = clock)
    val testKey = "test-key"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(testKey, defaultPolicy)
      check(result is Allowed)
      assertEquals((4 - i).toUInt(), result.remaining)
    }
    assertInstanceOf(Denied::class.java, algorithm.calculate(testKey, defaultPolicy))
    clock.advance(1.minutes + 1.seconds) // just past the boundary
    val result = assertInstanceOf(Allowed::class.java, algorithm.calculate(testKey, defaultPolicy))
    assertEquals(defaultPolicy.limit - 1u, result.remaining)
  }

  @Test
  fun `multiple keys have independent counts`() = runTest {
    val algorithm = InMemoryFixedWindow(clock = FakeClock())
    val secondPolicy = defaultPolicy.copy(limit = 6u)
    val testKey1 = "test-key"
    val testKey2 = "test-key2"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = algorithm.calculate(testKey1, defaultPolicy)
      val result2 = algorithm.calculate(testKey2, defaultPolicy)
      check(result is Allowed)
      check(result2 is Allowed)
      assertEquals((4 - i).toUInt(), result.remaining)
      assertEquals((4 - i).toUInt(), result2.remaining)
    }
    assertInstanceOf(Denied::class.java, algorithm.calculate(testKey1, defaultPolicy))
    assertInstanceOf(Allowed::class.java, algorithm.calculate(testKey2, secondPolicy))
  }

  @Test
  fun `concurrent access does not alter store behaviour`() = runBlocking {
    val algorithm = InMemoryFixedWindow()
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
