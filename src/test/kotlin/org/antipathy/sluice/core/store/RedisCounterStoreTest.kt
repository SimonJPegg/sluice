package org.antipathy.sluice.core.store

import kotlinx.coroutines.test.runTest
import org.antipathy.sluice.core.algorithm.FakeClock
import org.antipathy.sluice.core.algorithm.InMemoryFixedWindow
import org.antipathy.sluice.core.algorithm.RedisFixedWindow
import org.antipathy.sluice.core.model.AlgorithmType
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Denied
import org.antipathy.sluice.core.model.FailType
import org.antipathy.sluice.core.model.Failed
import org.antipathy.sluice.core.model.Policy
import org.antipathy.sluice.core.redis.RedisTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

class RedisCounterStoreTest: RedisTest() {

  private val defaultPolicy =
    Policy(
      id = "test-policy",
      limit = 5u,
      failType = FailType.OPEN,
      window = 1.minutes,
      algorithmType = AlgorithmType.FIXED_WINDOW,
    )

  @Test
  fun `validate testcontainer is working as expected`() {
    val commands = connection.sync()
    assertEquals("PONG", commands.ping())
  }

  @Test
  fun `unimplemented algorithm - returns Failed`() = runTest {
    val store =
      RedisCounterStore(mapOf(AlgorithmType.FIXED_WINDOW to RedisFixedWindow(connection)))
    val testKey = "test-key"
    assertInstanceOf(
      Failed::class.java,
      store.evaluate(testKey, defaultPolicy.copy(algorithmType = AlgorithmType.TOKEN_BUCKET)))
  }

  @Test
  fun `store fails open when the policy specifies it`() = runTest {
    val store =
      RedisCounterStore(mapOf(AlgorithmType.FIXED_WINDOW to RedisFixedWindow(connection)))
    val testKey = "test-key"
    connection.close()
    val result = assertInstanceOf(Allowed::class.java, store.evaluate(testKey, defaultPolicy))

    assertEquals(0u, result.remaining)
    assertEquals(defaultPolicy.window, result.resetIn)
  }

  @Test
  fun `store fails closed when the policy specifies it`() = runTest {
    val store =
      RedisCounterStore(mapOf(AlgorithmType.FIXED_WINDOW to RedisFixedWindow(connection)))
    val testKey = "test-key"
    val policy = defaultPolicy.copy(failType = FailType.CLOSED)
    connection.close()
    val result = assertInstanceOf(Denied::class.java, store.evaluate(testKey, policy))

    assertEquals(defaultPolicy.window, result.retryAfter)
  }
}
