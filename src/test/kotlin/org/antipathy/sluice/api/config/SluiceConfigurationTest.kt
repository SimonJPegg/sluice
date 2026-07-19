package org.antipathy.sluice.api.config

import io.ktor.server.config.ApplicationConfig
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.antipathy.sluice.api.exceptions.ConfigurationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SluiceConfigurationTest {

  @Test
  fun `should parse valid config with all fields`() {
    val config = ApplicationConfig("src/test/resources/config/valid-all-fields.yaml")

    val result = SluiceConfiguration.from(config)

    assertEquals("src/test/resources/policy/valid", result.policiesLocation)
    assertEquals("redis://localhost:6379", result.redisUrl)
    assertEquals(128, result.maxIdentifierLength)
  }

  @Test
  fun `should parse valid config without redis uri`() {
    val config = ApplicationConfig("src/test/resources/config/valid-no-redis.yaml")

    val result = SluiceConfiguration.from(config)

    assertEquals("src/test/resources/policy/valid", result.policiesLocation)
    assertNull(result.redisUrl)
  }

  @Test
  fun `should use default max identifier length when not specified`() {
    val config = ApplicationConfig("src/test/resources/config/valid-no-redis.yaml")

    val result = SluiceConfiguration.from(config)

    assertEquals(256, result.maxIdentifierLength)
  }

  @Test
  fun `should throw when policies location is missing`() {
    val config = ApplicationConfig("src/test/resources/config/missing-policies.yaml")

    val exception = assertThrows<ConfigurationException> { SluiceConfiguration.from(config) }
    assertTrue(exception.message!!.contains("policy location"))
  }

  @Test
  fun `should throw when policies location is blank`() {
    val config = ApplicationConfig("src/test/resources/config/blank-policies.yaml")

    val exception = assertThrows<ConfigurationException> { SluiceConfiguration.from(config) }
    assertTrue(exception.message!!.contains("policy location"))
  }

  @Test
  fun `should throw when policies location does not exist on disk`() {
    val config = ApplicationConfig("src/test/resources/config/nonexistent-path.yaml")

    val exception = assertThrows<ConfigurationException> { SluiceConfiguration.from(config) }
    assertTrue(exception.message!!.contains("does not exist"))
  }

  @Test
  fun `should throw when redis uri is malformed`() {
    val config = ApplicationConfig("src/test/resources/config/bad-redis-uri.yaml")

    val exception = assertThrows<ConfigurationException> { SluiceConfiguration.from(config) }
    assertTrue(exception.message!!.contains("Redis URI"))
  }

  @Test
  fun `should throw when max identifier length is zero`() {
    val config = ApplicationConfig("src/test/resources/config/zero-length.yaml")

    val exception = assertThrows<ConfigurationException> { SluiceConfiguration.from(config) }
    assertTrue(exception.message!!.contains("max identifier length"))
  }

  @Test
  fun `should throw when max identifier length is negative`() {
    val config = ApplicationConfig("src/test/resources/config/negative-length.yaml")

    val exception = assertThrows<ConfigurationException> { SluiceConfiguration.from(config) }
    assertTrue(exception.message!!.contains("max identifier length"))
  }

  @Test
  fun `should report all validation errors not just the first`() {
    val config = ApplicationConfig("src/test/resources/config/multiple-errors.yaml")

    val exception = assertThrows<ConfigurationException> { SluiceConfiguration.from(config) }
    // Primary exception thrown, additional errors attached as suppressed
    assertTrue(exception.suppressed.isNotEmpty())
  }
}
