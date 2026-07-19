package org.antipathy.sluice.api.store

import io.lettuce.core.RedisException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.antipathy.sluice.api.metrics.Metrics
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.policy.AlgorithmType
import org.antipathy.sluice.core.policy.FailType
import org.antipathy.sluice.core.policy.Policy
import org.antipathy.sluice.core.store.CounterStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows

class InstrumentedCounterStoreTest {

  private val delegate = mockk<CounterStore>()
  private val metrics = mockk<Metrics>(relaxed = true)
  private val store = InstrumentedCounterStore(delegate, metrics)
  private val defaaltPolicy =
      Policy("somePolicy", 100u, FailType.OPEN, 1.minutes, AlgorithmType.FIXED_WINDOW)

  @Test
  fun `should delegate evaluate to underlying store`() = runTest {
    coEvery { delegate.evaluate(any(), any()) } returns Allowed(5u, 60.seconds)
    val result = assertInstanceOf<Allowed>(store.evaluate("key", defaaltPolicy))
    assertEquals(5u, result.remaining)
    assertEquals(60.seconds, result.resetIn)
  }

  @Test
  fun `should record store duration on successful evaluation`() = runTest {
    coEvery { delegate.evaluate(any(), any()) } returns Allowed(5u, 60.seconds)
    assertInstanceOf<Allowed>(store.evaluate("key", defaaltPolicy))
    coVerify { metrics.trackStoreDuration(eq("evaluate"), any()) }
  }

  @Test
  fun `should record store duration on failed evaluation`() = runTest {
    coEvery { delegate.evaluate(any(), any()) } throws RuntimeException("Not cool!")
    try {
      store.evaluate("key", defaaltPolicy)
    } catch (_: RuntimeException) {}
    coVerify { metrics.trackStoreDuration(eq("evaluate"), any()) }
  }

  @Test
  fun `should record store error when delegate throws`() = runTest {
    coEvery { delegate.evaluate(any(), any()) } throws RuntimeException("Not cool!")
    assertThrows<RuntimeException> { store.evaluate("key", defaaltPolicy) }
    coVerify { metrics.trackStoreError(any()) }
  }

  @Test
  fun `should rethrow exception from delegate`() = runTest {
    coEvery { delegate.evaluate(any(), any()) } throws RuntimeException("Not cool!")
    assertThrows<RuntimeException> { store.evaluate("key", defaaltPolicy) }
  }

  @Test
  fun `should record error type from exception class name`() = runTest {
    coEvery { delegate.evaluate(any(), any()) } throws RedisException("connection reset")
    assertThrows<RedisException> { store.evaluate("key", defaaltPolicy) }
    coVerify { metrics.trackStoreError(eq("RedisException")) }
  }
}
