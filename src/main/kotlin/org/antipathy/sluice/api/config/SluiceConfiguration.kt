package org.antipathy.sluice.api.config

import io.ktor.server.config.ApplicationConfig
import io.lettuce.core.RedisURI
import java.nio.file.Paths
import kotlin.io.path.exists
import org.antipathy.sluice.api.exceptions.ConfigurationException
import org.slf4j.LoggerFactory

/** Minimal required config for sluice */
data class SluiceConfiguration(
    val policiesLocation: String,
    val redisUrl: String?,
    val maxIdentifierLength: Int = 256
) {

  companion object {
    private val logger = LoggerFactory.getLogger(SluiceConfiguration::class.java)
    private const val DEFAULT_MAX_IDENTIFIER_LENGTH = 256

    @Suppress("CyclomaticComplexMethod") // linear validation chain, logic is easy-peasy
    fun from(config: ApplicationConfig): SluiceConfiguration {
      val exceptions = mutableListOf<ConfigurationException>()
      val redisUrl = config.propertyOrNull("rate-limit.backend.redis-uri")?.getString()
      val maxIdentifierLength =
          config.propertyOrNull("rate-limit.validation.max-identifier-length")?.getString()?.toInt()
              ?: DEFAULT_MAX_IDENTIFIER_LENGTH
      val policiesLocation =
          config.propertyOrNull("rate-limit.policies.location")?.getString() ?: ""

      if (maxIdentifierLength < 1) {
        exceptions.add(ConfigurationException("max identifier length must be greater than 1"))
      }

      if (policiesLocation.isBlank()) {
        exceptions.add(ConfigurationException("policy location is empty"))
      } else if (!Paths.get(policiesLocation).exists()) {
        exceptions.add(ConfigurationException("policy location does not exist"))
      }

      if (!redisUrl.isNullOrBlank()) {
        try {
          RedisURI.create(redisUrl)
        } catch (e: IllegalArgumentException) {
          exceptions.add(ConfigurationException("invalid Redis URI: ${e.message}"))
        }
      }

      if (exceptions.isNotEmpty()) {
        logger.error("Configuration errors detected")
        val primary = exceptions.first()
        exceptions.drop(1).forEach { exception ->
          logger.error(exception.message)
          primary.addSuppressed(exception)
        }
        throw primary
      }

      return SluiceConfiguration(policiesLocation, redisUrl, maxIdentifierLength)
    }
  }
}
