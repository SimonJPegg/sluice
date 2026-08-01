package org.antipathy.sluice.core.policy

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlinx.serialization.Serializable
import net.mamoe.yamlkt.Yaml
import org.antipathy.sluice.core.exceptions.InvalidPolicyConfigurationException

object PolicyReader {
  private fun readYamlFiles(policyDir: Path): List<Policy> {
    val entries = validatedDirectoryEntries(policyDir)
    return entries
        .filter {
          !it.isDirectory() && (it.toString().endsWith(".yaml") || it.toString().endsWith(".yml"))
        }
        .flatMap { Yaml.decodeFromString(PolicyList.serializer(), it.readText()).policies }
  }

  /** Fails fast if the directory is missing, not a directory, or empty. */
  private fun validatedDirectoryEntries(policyDir: Path): List<Path> {
    if (!policyDir.exists() || !policyDir.isDirectory()) {
      throw InvalidPolicyConfigurationException(
          "Policy directory $policyDir does not exist or is not a directory"
      )
    }
    val entries = policyDir.listDirectoryEntries()
    if (entries.isEmpty()) {
      throw InvalidPolicyConfigurationException("Policy directory $policyDir is empty")
    }
    return entries
  }

  fun read(policyPath: Path): Map<String, Policy> {
    val policies = readYamlFiles(policyPath)
    if (policies.isEmpty()) {
      throw InvalidPolicyConfigurationException("No policies found in policy directory")
    }
    return policies.fold(emptyMap()) { acc, policy ->
      if (acc.containsKey(policy.id)) {
        throw InvalidPolicyConfigurationException("Duplicate policy ID: ${policy.id}")
      }
      acc + (policy.id to policy.validate())
    }
  }
}

/** Deserialisation target. Wraps the list so YAML has a root key. */
@Serializable private data class PolicyList(val policies: List<Policy>)
