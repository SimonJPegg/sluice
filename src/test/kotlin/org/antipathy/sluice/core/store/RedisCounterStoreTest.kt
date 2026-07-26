package org.antipathy.sluice.core.store

import io.mockk.coEvery
import io.mockk.mockk
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.antipathy.sluice.core.algorithm.RedisFixedWindow
import org.antipathy.sluice.core.algorithm.redis.ScriptLoader
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Failed
import org.antipathy.sluice.core.model.FailureCategory
import org.antipathy.sluice.core.policy.AlgorithmType
import org.antipathy.sluice.core.policy.FailType
import org.antipathy.sluice.core.policy.Policy
import org.antipathy.sluice.redis.RedisTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class RedisCounterStoreTest : RedisTest() {

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
        RedisCounterStore(
            mapOf(AlgorithmType.FIXED_WINDOW to RedisFixedWindow(ScriptLoader(connection)))
        )
    val testKey = "test-key"
    assertInstanceOf(
        Failed::class.java,
        store.evaluate(testKey, defaultPolicy.copy(algorithmType = AlgorithmType.TOKEN_BUCKET)),
    )
  }

  @Test
  fun `store returns failed when redis connection times out`() = runBlocking {
    val hangingAlgorithm =
        mockk<RedisFixedWindow> {
          coEvery { calculate(any(), any()) } coAnswers
              {
                delay(10.minutes)
                Allowed(0u, 1.milliseconds)
              }
        }

    val store =
        RedisCounterStore(
            mapOf(AlgorithmType.FIXED_WINDOW to hangingAlgorithm),
            1.milliseconds,
        )
    val testKey = "test-key"
    val policy = defaultPolicy.copy(failType = FailType.CLOSED)
    connection.close()
    val result = assertInstanceOf(Failed::class.java, store.evaluate(testKey, policy))

    assertEquals(FailureCategory.STORE_TIMEOUT, result.failureCategory)
  }

  @Test
  fun `store returns failed when connection unavailable`() = runTest {
    val store =
        RedisCounterStore(
            mapOf(AlgorithmType.FIXED_WINDOW to RedisFixedWindow(ScriptLoader(connection)))
        )
    val testKey = "test-key"
    val policy = defaultPolicy.copy(failType = FailType.CLOSED)
    connection.close()
    val result = assertInstanceOf(Failed::class.java, store.evaluate(testKey, policy))
    assertEquals(FailureCategory.STORE_UNAVAILABLE, result.failureCategory)
  }
}
