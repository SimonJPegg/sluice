package org.antipathy.sluice.api.model

import org.antipathy.sluice.core.policy.PolicyRegistry

/**
 * Whitelist of allowed characters in identifiers. Rejects unicode, null bytes, and injection
 * attempts.
 */
private val identifierPattern = Regex("^[a-zA-Z0-9\\-_:]+$")

/** Validates and resolves a raw request into either a valid request or a specific error. */
@Suppress("CyclomaticComplexMethod") // linear chain, each branch is independent
fun RateLimitRequest.validate(
    policyRegistry: PolicyRegistry,
    maxIdentifierLength: Int,
): ValidationResult =
    when {
      key.isBlank() -> MissingKeyRequest()
      policyId.isBlank() -> MissingPolicyRequest()
      !key.matches(identifierPattern) ->
          InvalidKeyRequest("Key does not match '${identifierPattern}'")
      !policyId.matches(identifierPattern) ->
          InvalidPolicyRequest("Policy ID does not match '${identifierPattern}'")
      key.length > maxIdentifierLength ->
          InvalidKeyRequest("Key length must not exceed $maxIdentifierLength")
      policyId.length > maxIdentifierLength ->
          InvalidPolicyRequest("Policy ID length must not exceed $maxIdentifierLength")

      else ->
          policyRegistry.get(policyId)?.let { ValidRequest(key, it) }
              ?: PolicyNotFoundRequest(policyId)
    }
