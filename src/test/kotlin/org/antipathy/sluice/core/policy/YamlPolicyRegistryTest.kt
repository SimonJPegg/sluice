package org.antipathy.sluice.core.policy

import kotlin.time.Duration.Companion.minutes
import org.antipathy.sluice.core.exceptions.InvalidPolicyConfigurationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail

class YamlPolicyRegistryTest {

  @Test
  @Suppress("LongMethod") // there's very little logic here
  fun `Valid config, loads correctly, get returns the right policy`() {
    val path = "/policy/valid"
    val dir =
        YamlPolicyRegistryTest::class.java.getResource(path)
            ?: fail { "Unable to load resource $path" }
    val registry = YamlPolicyRegistry(dir.path)

    val apiGlobal = registry.get("api-global")
    assertEquals(
        Policy(
            id = "api-global",
            limit = 100u,
            window = 1.minutes,
            algorithmType = AlgorithmType.FIXED_WINDOW,
            failType = FailType.OPEN,
        ),
        apiGlobal,
    )

    val apiHeavy = registry.get("api-heavy")
    assertEquals(
        Policy(
            id = "api-heavy",
            limit = 10u,
            window = 1.minutes,
            algorithmType = AlgorithmType.SLIDING_WINDOW_COUNTER,
            failType = FailType.OPEN,
        ),
        apiHeavy,
    )

    val loginBruteForce = registry.get("login-brute-force")
    assertEquals(
        Policy(
            id = "login-brute-force",
            limit = 5u,
            window = 5.minutes,
            algorithmType = AlgorithmType.SLIDING_WINDOW_LOG,
            failType = FailType.CLOSED,
        ),
        loginBruteForce,
    )

    val loginPerIp = registry.get("login-per-ip")
    assertEquals(
        Policy(
            id = "login-per-ip",
            limit = 20u,
            window = 10.minutes,
            algorithmType = AlgorithmType.TOKEN_BUCKET,
            failType = FailType.CLOSED,
        ),
        loginPerIp,
    )

    val webhookRetry = registry.get("webhook-retry")
    assertEquals(
        Policy(
            id = "webhook-retry",
            limit = 3u,
            window = 1.minutes,
            algorithmType = AlgorithmType.TOKEN_BUCKET,
            failType = FailType.CLOSED,
        ),
        webhookRetry,
    )
  }

  @Test
  fun `Missing directory, throws at construction`() {
    assertThrows<InvalidPolicyConfigurationException> { YamlPolicyRegistry("/does/not/exist") }
  }

  @Test
  fun `Empty directory, throws at construction`() {
    val dir = YamlPolicyRegistryTest::class.java.getResource("/policy/emptyDir")!!
    assertThrows<InvalidPolicyConfigurationException> { YamlPolicyRegistry(dir.toString()) }
  }

  @Test
  fun `Duplicate policy ID, throws at construction`() {
    assertThrows<InvalidPolicyConfigurationException> {
      val path = "/policy/dupes"
      val dir =
          YamlPolicyRegistryTest::class.java.getResource(path)
              ?: fail { "Unable to load resource $path" }
      YamlPolicyRegistry(dir.path)
    }
  }

  @Test
  fun `blank ID throws at construction these hit Policy init validation`() {
    assertThrows<InvalidPolicyConfigurationException> {
      val path = "/policy/blank"
      val dir =
          YamlPolicyRegistryTest::class.java.getResource(path)
              ?: fail { "Unable to load resource $path" }
      YamlPolicyRegistry(dir.path)
    }
  }

  @Test
  fun `zero window  throws at construction these hit Policy init validation`() {
    assertThrows<InvalidPolicyConfigurationException> {
      val path = "/policy/zeroWindow"
      val dir =
          YamlPolicyRegistryTest::class.java.getResource(path)
              ?: fail { "Unable to load resource $path" }
      YamlPolicyRegistry(dir.path)
    }
  }

  @Test
  fun `Unknown policy ID, get returns null`() {
    val path = "/policy/valid"
    val dir =
        YamlPolicyRegistryTest::class.java.getResource(path)
            ?: fail { "Unable to load resource $path" }
    val registry = YamlPolicyRegistry(dir.path)
    assertTrue(registry.get("does-not-exist") == null)
  }
}
