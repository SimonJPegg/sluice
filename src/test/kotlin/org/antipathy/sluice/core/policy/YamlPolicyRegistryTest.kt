package org.antipathy.sluice.core.policy

import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

class YamlPolicyRegistryTest {

  private fun registryFromPath(resourcePath: String): YamlPolicyRegistry {
    val dir =
        YamlPolicyRegistryTest::class.java.getResource(resourcePath)
            ?: fail { "Unable to load resource $resourcePath" }
    val policyWatcher = PolicyWatcher(Paths.get(dir.path))
    policyWatcher.load()
    return YamlPolicyRegistry(policyWatcher)
  }

  @Test
  fun `get returns the correct policy by ID`() {
    val registry = registryFromPath("/policy/valid")
    val policy = registry.get("api-global")
    assertEquals("api-global", policy?.id)
  }

  @Test
  fun `get returns null for unknown policy ID`() {
    val registry = registryFromPath("/policy/valid")
    assertNull(registry.get("does-not-exist"))
  }

  @Test
  fun `requiredAlgorithms returns distinct algorithm types from all policies`() {
    val registry = registryFromPath("/policy/valid")
    val algorithms = registry.requiredAlgorithms()
    assertEquals(
        setOf(
            AlgorithmType.FIXED_WINDOW,
            AlgorithmType.SLIDING_WINDOW_COUNTER,
            AlgorithmType.SLIDING_WINDOW_LOG,
            AlgorithmType.TOKEN_BUCKET,
        ),
        algorithms,
    )
  }

  @Test
  fun `all returns every loaded policy`() {
    val registry = registryFromPath("/policy/valid")
    assertEquals(5, registry.all().size)
  }
}
