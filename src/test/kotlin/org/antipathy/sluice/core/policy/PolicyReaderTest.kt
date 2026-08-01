package org.antipathy.sluice.core.policy

import java.nio.file.Paths
import kotlin.time.Duration.Companion.minutes
import org.antipathy.sluice.core.exceptions.InvalidPolicyConfigurationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail

class PolicyReaderTest {

  @Test
  fun `valid directory loads all policies with correct values`() {
    val path = "/policy/valid"
    val dir =
        PolicyReaderTest::class.java.getResource(path) ?: fail { "Unable to load resource $path" }
    val policies = PolicyReader.read(Paths.get(dir.path))

    assertEquals(
        Policy(
            id = "api-global",
            limit = 100u,
            window = 1.minutes,
            algorithmType = AlgorithmType.FIXED_WINDOW,
            failType = FailType.OPEN,
        ),
        policies["api-global"],
    )

    assertEquals(
        Policy(
            id = "api-heavy",
            limit = 10u,
            window = 1.minutes,
            algorithmType = AlgorithmType.SLIDING_WINDOW_COUNTER,
            failType = FailType.OPEN,
        ),
        policies["api-heavy"],
    )

    assertEquals(
        Policy(
            id = "login-brute-force",
            limit = 5u,
            window = 5.minutes,
            algorithmType = AlgorithmType.SLIDING_WINDOW_LOG,
            failType = FailType.CLOSED,
        ),
        policies["login-brute-force"],
    )

    assertEquals(
        Policy(
            id = "login-per-ip",
            limit = 20u,
            window = 10.minutes,
            algorithmType = AlgorithmType.TOKEN_BUCKET,
            failType = FailType.CLOSED,
        ),
        policies["login-per-ip"],
    )

    assertEquals(
        Policy(
            id = "webhook-retry",
            limit = 3u,
            window = 1.minutes,
            algorithmType = AlgorithmType.TOKEN_BUCKET,
            failType = FailType.CLOSED,
        ),
        policies["webhook-retry"],
    )
  }

  @Test
  fun `missing directory throws InvalidPolicyConfigurationException`() {
    assertThrows<InvalidPolicyConfigurationException> {
      PolicyReader.read(Paths.get("/does/not/exist"))
    }
  }

  @Test
  fun `empty directory throws InvalidPolicyConfigurationException`() {
    val dir = PolicyReaderTest::class.java.getResource("/policy/emptyDir")!!
    assertThrows<InvalidPolicyConfigurationException> { PolicyReader.read(Paths.get(dir.path)) }
  }

  @Test
  fun `duplicate policy ID throws InvalidPolicyConfigurationException`() {
    val path = "/policy/dupes"
    val dir =
        PolicyReaderTest::class.java.getResource(path) ?: fail { "Unable to load resource $path" }
    assertThrows<InvalidPolicyConfigurationException> { PolicyReader.read(Paths.get(dir.path)) }
  }

  @Test
  fun `blank policy ID throws InvalidPolicyConfigurationException`() {
    val path = "/policy/blank"
    val dir =
        PolicyReaderTest::class.java.getResource(path) ?: fail { "Unable to load resource $path" }
    assertThrows<InvalidPolicyConfigurationException> { PolicyReader.read(Paths.get(dir.path)) }
  }

  @Test
  fun `zero window throws InvalidPolicyConfigurationException`() {
    val path = "/policy/zeroWindow"
    val dir =
        PolicyReaderTest::class.java.getResource(path) ?: fail { "Unable to load resource $path" }
    assertThrows<InvalidPolicyConfigurationException> { PolicyReader.read(Paths.get(dir.path)) }
  }
}
