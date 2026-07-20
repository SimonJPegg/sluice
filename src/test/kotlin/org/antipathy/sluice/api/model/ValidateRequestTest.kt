package org.antipathy.sluice.api.model

import io.mockk.every
import io.mockk.mockk
import org.antipathy.sluice.core.policy.Policy
import org.antipathy.sluice.core.policy.PolicyRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class ValidateRequestTest {

  private val maxKeyLength = 256
  private val registry = mockk<PolicyRegistry>()
  private val testPolicy = mockk<Policy>()

  @Test
  fun `returns MissingKeyRequest when key is blank`() {
    val request = RateLimitRequest(key = "", policyId = "some-policy")
    val result = request.validate(registry, maxKeyLength)
    assertInstanceOf<MissingKeyRequest>(result)
  }

  @Test
  fun `returns MissingPolicyRequest when policy ID is blank`() {
    val request = RateLimitRequest(key = "valid-key", policyId = "")
    val result = request.validate(registry, maxKeyLength)
    assertInstanceOf<MissingPolicyRequest>(result)
  }

  @Test
  fun `returns InvalidKeyRequest when key contains invalid characters`() {
    val request = RateLimitRequest(key = "key with spaces", policyId = "some-policy")
    val result = request.validate(registry, maxKeyLength)
    assertInstanceOf<InvalidKeyRequest>(result)
  }

  @Test
  fun `returns InvalidKeyRequest when key exceeds max length`() {
    val request = RateLimitRequest(key = "a".repeat(257), policyId = "some-policy")
    val result = request.validate(registry, maxKeyLength)
    assertInstanceOf<InvalidKeyRequest>(result)
  }

  @Test
  fun `returns PolicyNotFoundRequest when policy does not exist in registry`() {
    every { registry.get("unknown") } returns null
    val request = RateLimitRequest(key = "valid-key", policyId = "unknown")
    val result = request.validate(registry, maxKeyLength)
    assertInstanceOf<PolicyNotFoundRequest>(result)
  }

  @Test
  fun `returns ValidRequest when key and policy are valid`() {
    every { registry.get("api-global") } returns testPolicy
    val request = RateLimitRequest(key = "valid-key", policyId = "api-global")
    val result = request.validate(registry, maxKeyLength)
    assertInstanceOf<ValidRequest>(result)
  }

  @Test
  fun `accepts keys with letters numbers dashes underscores and colons`() {
    every { registry.get("api-global") } returns testPolicy
    val request = RateLimitRequest(key = "valid:key_2:1-3", policyId = "api-global")
    val result = request.validate(registry, maxKeyLength)
    assertInstanceOf<ValidRequest>(result)
  }

  @Test
  fun `returns InvalidPolicyRequest when policy ID contains invalid characters`() {
    val request = RateLimitRequest(key = "valid-key", policyId = "policy with spaces")
    val result = request.validate(registry, maxKeyLength)
    assertInstanceOf<InvalidPolicyRequest>(result)
  }
}
