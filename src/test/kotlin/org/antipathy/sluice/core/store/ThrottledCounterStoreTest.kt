package org.antipathy.sluice.core.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.antipathy.sluice.core.exceptions.InvalidPolicyConfigurationException
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Failed
import org.antipathy.sluice.core.model.FailureCategory
import org.antipathy.sluice.core.model.RateLimitResponse
import org.antipathy.sluice.core.policy.AlgorithmType
import org.antipathy.sluice.core.policy.FailType
import org.antipathy.sluice.core.policy.Policy
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows

class ThrottledCounterStoreTest {

  private val policy = Policy("test", 100u, FailType.OPEN, 60.seconds, AlgorithmType.FIXED_WINDOW)

  private fun allowingStore(d: Duration): CounterStore =
      object : CounterStore {
        override suspend fun evaluate(key: String, policy: Policy): RateLimitResponse {
          delay(d)
          return Allowed(99u, policy.window)
        }
      }

  @Test
  fun `delegates when under threshold`() = runTest {
    val store = ThrottledCounterStore(5, allowingStore(0.seconds))
    assertInstanceOf<Allowed>(store.evaluate("key", policy))
  }

  @Test
  fun `returns Failed with OVERLOADED when at threshold`() = runTest {
    val store = ThrottledCounterStore(1, allowingStore(1.seconds))
    async { // run concurrently
      assertInstanceOf<Allowed>(store.evaluate("key", policy))
    }
    delay(50.milliseconds)
    assertInstanceOf<Failed>(store.evaluate("key", policy))
  }

  @Test
  fun `retryAfter is policy window on rejection`() = runTest {
    val store = ThrottledCounterStore(0, allowingStore(0.seconds))
    val result = assertInstanceOf<Failed>(store.evaluate("key", policy))

    assertEquals(FailureCategory.OVERLOADED, result.failureCategory)
  }

  @Test
  fun `counter decrements after delegate completes`() = runTest {
    val store = ThrottledCounterStore(1, allowingStore(1.seconds))
    assertInstanceOf<Allowed>(store.evaluate("key", policy))
    assertInstanceOf<Allowed>(store.evaluate("key", policy))
  }

  @Test
  fun `counter decrements even when delegate throws`() = runTest {
    var shouldThow = true
    fun THrowingStore(): CounterStore =
        object : CounterStore {
          override suspend fun evaluate(key: String, policy: Policy): RateLimitResponse {
            if (shouldThow) {
              throw InvalidPolicyConfigurationException("Welcome to $policy")
            } else {
              return Allowed(99u, policy.window)
            }
          }
        }

    val store = ThrottledCounterStore(1, THrowingStore())
    assertThrows<InvalidPolicyConfigurationException> { store.evaluate("key", policy) }
    shouldThow = false
    assertInstanceOf<Allowed>(store.evaluate("key", policy))
  }

  @Test
  fun `concurrent requests - only maxRequests get through`() = runTest {
    val store = ThrottledCounterStore(100, allowingStore(1.seconds))
    withContext(Dispatchers.Default) {
      val threads = List(200) { async { store.evaluate("key", policy) } }
      val result = threads.awaitAll()
      val (allowed, denied) = result.partition { it is Allowed }
      Assertions.assertEquals(
          100,
          allowed.size,
      )
      Assertions.assertEquals(
          100,
          denied.size,
      )
    }
  }
}
