package org.antipathy.sluice.core.store

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.antipathy.sluice.core.algorithm.FakeClock
import org.antipathy.sluice.core.algorithm.InMemoryFixedWindow
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Failed
import org.antipathy.sluice.core.model.RateLimitResponse
import org.antipathy.sluice.core.policy.AlgorithmType
import org.antipathy.sluice.core.policy.FailType
import org.antipathy.sluice.core.policy.Policy
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class CircuitBreakerCounterStoreTest {

  private val clock = FakeClock()

  private val openPolicy =
      Policy(
          id = "open-policy",
          limit = 10u,
          failType = FailType.OPEN,
          window = 1.minutes,
          algorithmType = AlgorithmType.FIXED_WINDOW,
      )

  private val closedPolicy = openPolicy.copy(id = "closed-policy", failType = FailType.CLOSED)

  private fun workingStore(): CounterStore =
      InMemoryCounterStore(
          algorithms = mapOf(AlgorithmType.FIXED_WINDOW to InMemoryFixedWindow(clock = clock))
      )

  private fun failingStore(): CounterStore =
      object : CounterStore {
        override suspend fun evaluate(key: String, policy: Policy) =
            Failed(reason = "Redis exploded", 1.seconds)
      }

  @Test
  fun `passes through when circuit is closed`() = runTest {
    val cb =
        CircuitBreakerCounterStore(
            workingStore(),
            failureThreshold = 3,
            resetTimeout = 30.seconds,
            clock = clock,
        )
    val result = cb.evaluate("key", openPolicy)
    assertInstanceOf(Allowed::class.java, result)
  }

  @Test
  fun `trips open after threshold failures`() = runTest {
    val cb =
        CircuitBreakerCounterStore(
            failingStore(),
            failureThreshold = 3,
            resetTimeout = 30.seconds,
            clock = clock,
        )
    repeat(3) { cb.evaluate("key", openPolicy) }
    val result = cb.evaluate("key", openPolicy)
    assertInstanceOf(Allowed::class.java, result) // fail-open policy
  }

  @Test
  fun `fail-closed policy gets Failed when circuit is open`() = runTest {
    val cb =
        CircuitBreakerCounterStore(
            failingStore(),
            failureThreshold = 3,
            resetTimeout = 30.seconds,
            clock = clock,
        )
    repeat(3) { cb.evaluate("key", closedPolicy) }
    val result = cb.evaluate("key", closedPolicy)
    assertInstanceOf(Failed::class.java, result)
  }

  @Test
  fun `resets failure count on success`() = runTest {
    var shouldFail = true
    val switchable =
        object : CounterStore {
          override suspend fun evaluate(key: String, policy: Policy): RateLimitResponse {
            return if (shouldFail) Failed(reason = "down", 1.seconds)
            else Allowed(9u, policy.window)
          }
        }

    val cb =
        CircuitBreakerCounterStore(
            switchable,
            failureThreshold = 3,
            resetTimeout = 10.seconds,
            clock = clock,
        )
    cb.evaluate("key", openPolicy)
    cb.evaluate("key", openPolicy)
    shouldFail = false
    cb.evaluate("key", openPolicy)
    shouldFail = true
    cb.evaluate("key", openPolicy)
    cb.evaluate("key", openPolicy)
    val result = cb.evaluate("key", openPolicy)
    assertInstanceOf(Failed::class.java, result) // called delegate, got Failed — not fast-fail
  }

  @Test
  fun `half-open probe success closes circuit`() = runTest {
    var shouldFail = true
    val switchable =
        object : CounterStore {
          override suspend fun evaluate(key: String, policy: Policy): RateLimitResponse {
            return if (shouldFail) Failed(reason = "down", 1.seconds)
            else Allowed(9u, policy.window)
          }
        }

    val cb =
        CircuitBreakerCounterStore(
            switchable,
            failureThreshold = 3,
            resetTimeout = 10.seconds,
            clock = clock,
        )
    repeat(3) { cb.evaluate("key", openPolicy) }
    clock.advance(11.seconds)
    shouldFail = false
    val probeResult = cb.evaluate("key", openPolicy)
    assertInstanceOf(Allowed::class.java, probeResult)
    val result = cb.evaluate("key", openPolicy)
    assertInstanceOf(Allowed::class.java, result)
  }

  @Test
  fun `half-open probe failure re-opens circuit`() = runTest {
    val cb =
        CircuitBreakerCounterStore(
            failingStore(),
            failureThreshold = 3,
            resetTimeout = 10.seconds,
            clock = clock,
        )
    repeat(3) { cb.evaluate("key", openPolicy) }
    clock.advance(11.seconds)
    cb.evaluate("key", openPolicy)
    val result = cb.evaluate("key", openPolicy)
    assertInstanceOf(Allowed::class.java, result) // fail-open, no delegate call
  }
}
