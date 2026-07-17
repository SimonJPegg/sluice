package org.antipathy.sluice.core.store

import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.antipathy.sluice.core.algorithm.FakeClock
import org.antipathy.sluice.core.algorithm.InMemoryFixedWindow
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Failed
import org.antipathy.sluice.core.policy.AlgorithmType
import org.antipathy.sluice.core.policy.FailType
import org.antipathy.sluice.core.policy.Policy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class InMemoryCounterStoreTest {
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
    val store =
        InMemoryCounterStore(
            algorithms =
                mapOf(AlgorithmType.FIXED_WINDOW to InMemoryFixedWindow(clock = FakeClock())))
    val testKey = "test-key"
    val result = assertInstanceOf(Allowed::class.java, store.evaluate(testKey, defaultPolicy))

    assertEquals(defaultPolicy.limit - 1u, result.remaining)
    assertEquals(defaultPolicy.window, result.resetIn)
  }

  @Test
  fun `unimplemented algorithm - returns Failed`() = runTest {
    val store =
        InMemoryCounterStore(
            algorithms =
                mapOf(AlgorithmType.FIXED_WINDOW to InMemoryFixedWindow(clock = FakeClock())))
    val testKey = "test-key"
    assertInstanceOf(
        Failed::class.java,
        store.evaluate(testKey, defaultPolicy.copy(algorithmType = AlgorithmType.TOKEN_BUCKET)))
  }
}
