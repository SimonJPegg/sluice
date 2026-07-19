package org.antipathy.sluice.core.policy

/** Allow policies to be retrieved by their identifier */
interface PolicyRegistry {
  fun get(policyId: String): Policy?

  fun all(): Set<Policy>

  fun requiredAlgorithms(): Set<AlgorithmType>
}
