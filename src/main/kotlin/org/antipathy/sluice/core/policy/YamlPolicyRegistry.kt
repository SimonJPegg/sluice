package org.antipathy.sluice.core.policy

/** Loads policies from YAML files in a directory at startup. Read-only after construction. */
class YamlPolicyRegistry(val policyWatcher: PolicyWatcher) : PolicyRegistry {

  override fun get(policyId: String): Policy? {

    return policyWatcher.getPolicies().get(policyId)
  }

  override fun requiredAlgorithms(): Set<AlgorithmType> {
    return policyWatcher.getPolicies().values.map { it.algorithmType }.toSet()
  }

  override fun all(): Set<Policy> {
    return policyWatcher.getPolicies().values.toSet()
  }
}
