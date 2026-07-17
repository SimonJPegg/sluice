package org.antipathy.sluice.core.policy

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlinx.serialization.Serializable
import net.mamoe.yamlkt.Yaml
import org.antipathy.sluice.core.exceptions.InvalidPolicyConfigurationException

/** Deserialisation target. Wraps the list so YAML has a root key. */
@Serializable private data class PolicyList(val policies: List<Policy>)

/** Loads policies from YAML files in a directory at startup. Read-only after construction. */
class YamlPolicyRegistry(private val policyDirectory: String) : PolicyRegistry {

  private val policyRegistry: Map<String, Policy>

  init {
    policyRegistry = constructRegistryFromPolicies(readYamlFiles())
  }

  private fun readYamlFiles(): List<Policy> {
    val entries = validatedDirectoryEntries()
    return entries
        .filter {
          !it.isDirectory() && (it.toString().endsWith(".yaml") || it.toString().endsWith(".yml"))
        }
        .flatMap { Yaml.decodeFromString(PolicyList.serializer(), it.readText()).policies }
  }

  /** Fails fast if the directory is missing, not a directory, or empty. */
  private fun validatedDirectoryEntries(): List<Path> {
    val policyDir = Paths.get(policyDirectory)
    if (!policyDir.exists() || !policyDir.isDirectory()) {
      throw InvalidPolicyConfigurationException(
          "Policy directory $policyDirectory does not exist or is not a directory")
    }
    val entries = policyDir.listDirectoryEntries()
    if (entries.isEmpty()) {
      throw InvalidPolicyConfigurationException("Policy directory $policyDirectory is empty")
    }
    return entries
  }

  /** Validates no duplicates and builds the lookup map in one pass. */
  private fun constructRegistryFromPolicies(policies: List<Policy>): Map<String, Policy> {
    if (policies.isEmpty()) {
      throw InvalidPolicyConfigurationException(
          "No policies found in policy directory $policyDirectory")
    }
    return policies.fold(emptyMap()) { acc, policy ->
      if (acc.containsKey(policy.id)) {
        throw InvalidPolicyConfigurationException("Duplicate policy ID: ${policy.id}")
      }
      acc + (policy.id to policy.validate())
    }
  }

  override fun get(policyId: String): Policy? {
    return policyRegistry.get(policyId)
  }
}
