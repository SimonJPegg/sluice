package org.antipathy.sluice.api.model

import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Denied
import org.antipathy.sluice.core.model.Failed
import org.antipathy.sluice.core.policy.AlgorithmType
import org.antipathy.sluice.core.policy.FailType
import org.antipathy.sluice.core.policy.Policy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class ToProcessedTest {

  @Test
  fun `maps Allowed to AllowedRequest with correct values`() {
    val allowed = Allowed(1u, 1.seconds)
    val policy = Policy("1", 1u, FailType.OPEN, 1.seconds, AlgorithmType.TOKEN_BUCKET)
    val result = assertInstanceOf<AllowedRequest>(allowed.toProcessed(policy))
    assertEquals(allowed.resetIn, result.resetIn)
    assertEquals(allowed.remaining, result.remaining.toUInt())
    assertEquals(policy.limit, result.limit.toUInt())
  }

  @Test
  fun `maps Denied to DeniedRequest with retry duration`() {
    val denied = Denied(1.seconds)
    val policy = Policy("1", 1u, FailType.OPEN, 1.seconds, AlgorithmType.TOKEN_BUCKET)
    val result = assertInstanceOf<DeniedRequest>(denied.toProcessed(policy))
    assertEquals(denied.retryAfter, result.retryAfter)
  }

  @Test
  fun `maps Failed to FailedRequest with reason`() {
    val failed = Failed("Computers, how do they work?", 1.seconds)
    val policy = Policy("1", 1u, FailType.OPEN, 1.seconds, AlgorithmType.TOKEN_BUCKET)
    val result = assertInstanceOf<FailedRequest>(failed.toProcessed(policy))
    assertEquals(failed.reason, result.reason)
  }
}
