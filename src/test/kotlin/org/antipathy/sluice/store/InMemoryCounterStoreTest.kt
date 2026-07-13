package org.antipathy.sluice.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.antipathy.sluice.model.AlgorithmType
import org.antipathy.sluice.model.Allowed
import org.antipathy.sluice.model.Denied
import org.antipathy.sluice.model.Failed
import org.antipathy.sluice.model.FailType
import org.antipathy.sluice.model.Policy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant


private class FakeClock(var now: Instant = Instant.fromEpochSeconds(0)) : Clock {
  fun advance(duration: Duration) { now = now.plus(duration) }
  override fun now(): Instant = now
}

class InMemoryCounterStoreTest {
  private val defaultPolicy = Policy(
    id = "test-policy",
    limit = 5u,
    failType = FailType.OPEN,
    window = 1.minutes,
    algorithmType = AlgorithmType.FIXED_WINDOW,
  )

  @Test
  fun `first request returns Allowed with remaining = limit - 1`() = runTest {
    val store = InMemoryCounterStore(clock = FakeClock())
    val testKey = "test-key"
    val result = assertInstanceOf(Allowed::class.java,store.evaluate(testKey,defaultPolicy))

    assertEquals(defaultPolicy.limit-1u,result.remaining)
    assertEquals(defaultPolicy.window,result.resetIn)
  }

  @Test
  fun `multiple increments — remaining decreases with each request`() = runTest {
    val store = InMemoryCounterStore(clock = FakeClock())
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = store.evaluate("key",defaultPolicy)
      check(result is Allowed)
      assertEquals((4 - i).toUInt(),result.remaining)
    }
  }

  @Test
  fun `at-limit — request number limit+1 returns Denied` () = runTest {
    val store = InMemoryCounterStore(clock = FakeClock())
    val testKey = "test-key"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = store.evaluate(testKey,defaultPolicy)
      check(result is Allowed)
      assertEquals((4 - i).toUInt(),result.remaining)
    }
    val result = assertInstanceOf(Denied::class.java,store.evaluate(testKey,defaultPolicy))
    assertEquals(defaultPolicy.window,result.retryAfter)
  }


  @Test
  fun `window expiry — after advancing clock past window, counter resets`() = runTest {
    val clock = FakeClock()
    val store = InMemoryCounterStore(clock = clock)
    val testKey = "test-key"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = store.evaluate(testKey,defaultPolicy)
      check(result is Allowed)
      assertEquals((4 - i).toUInt(),result.remaining)
    }
    assertInstanceOf(Denied::class.java,store.evaluate(testKey,defaultPolicy))
    clock.advance(1.minutes + 1.seconds) // just past the boundary
    val result = assertInstanceOf(Allowed::class.java,store.evaluate(testKey,defaultPolicy))
    assertEquals(defaultPolicy.limit -1u, result.remaining)
  }

  @Test
  fun `unimplemented algorithm — returns Failed` () = runTest {
    val store = InMemoryCounterStore(clock = FakeClock())
    val testKey = "test-key"
    assertInstanceOf(
      Failed::class.java,
        store.evaluate(testKey,
      defaultPolicy.copy(algorithmType = AlgorithmType.TOKEN_BUCKET)))
  }

  @Test
  fun `multiple keys have independent counts`() = runTest {
    val store = InMemoryCounterStore(clock = FakeClock())
    val secondPolicy = defaultPolicy.copy(limit = 6u)
    val testKey1 = "test-key"
    val testKey2 = "test-key2"
    repeat(defaultPolicy.limit.toInt()) { i ->
      val result = store.evaluate(testKey1,defaultPolicy)
      val result2 = store.evaluate(testKey2,defaultPolicy)
      check(result is Allowed)
      check(result2 is Allowed)
      assertEquals((4 - i).toUInt(),result.remaining)
      assertEquals((4 - i).toUInt(),result2.remaining)
    }
    assertInstanceOf(Denied::class.java,store.evaluate(testKey1,defaultPolicy))
    assertInstanceOf(Allowed::class.java,store.evaluate(testKey2,secondPolicy))
  }


  @Test
  fun `concurrent access does not alter store behaviour` () = runBlocking {
    val store = InMemoryCounterStore()
    val testKey = "test-key"
    val policy = defaultPolicy.copy(limit = 100u)
    withContext(Dispatchers.Default) {
      val threads = List(200) {async { store.evaluate(testKey,policy) } }
      val result = threads.awaitAll()
      val (allowed, denied) = result.partition { it is Allowed }
      assertEquals(100,allowed.size,)
      assertEquals(100, denied.size,)
    }

  }

}
