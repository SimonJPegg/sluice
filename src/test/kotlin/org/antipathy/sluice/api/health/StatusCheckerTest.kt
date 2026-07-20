package org.antipathy.sluice.api.health

import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class StatusCheckerTest {

  private val policyStatus = PolicyStatus(count = 5, loaded = "2026-07-19T10:00:00Z")

  @Test
  fun `should return in-memory status when no redis configured`() = runTest {
    val checker = StatusChecker(policyStatus) { StoreStatus("InMemory", "active", 0) }

    val result = checker.status()

    assertEquals("InMemory", result.storeStatus.type)
    assertEquals("active", result.storeStatus.status)
  }

  @Test
  fun `should return connected redis status when ping succeeds`() = runTest {
    val checker = StatusChecker(policyStatus) { StoreStatus("Redis", StoreStatus.HEALTHY, 2) }

    val result = checker.status()

    assertEquals("Redis", result.storeStatus.type)
    assertEquals(StoreStatus.HEALTHY, result.storeStatus.status)
    assertEquals(2, result.storeStatus.latencyMS)
  }

  @Test
  fun `should return failed redis status when ping throws`() = runTest {
    val checker = StatusChecker(policyStatus) { StoreStatus("Redis", StoreStatus.FAILED, 0) }

    val result = checker.status()

    assertEquals("Redis", result.storeStatus.type)
    assertEquals(StoreStatus.FAILED, result.storeStatus.status)
    assertEquals(0, result.storeStatus.latencyMS)
  }

  @Test
  fun `should include policy count and loaded timestamp`() = runTest {
    val checker = StatusChecker(policyStatus) { StoreStatus("InMemory", "active", 0) }

    val result = checker.status()

    assertEquals(5, result.policies.count)
    assertEquals("2026-07-19T10:00:00Z", result.policies.loaded)
  }
}
