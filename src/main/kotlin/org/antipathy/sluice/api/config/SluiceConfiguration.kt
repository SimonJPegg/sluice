package org.antipathy.sluice.api.config

import io.ktor.server.config.ApplicationConfig
import io.lettuce.core.RedisURI
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.antipathy.sluice.api.exceptions.ConfigurationException
import org.slf4j.LoggerFactory

data class CircuitBreaker(
    val failureThreshold: Int,
    val resetTimeout: Duration,
)

/** Minimal required config for sluice */
data class SluiceConfiguration(
    val policiesLocation: String,
    val redisUrl: String?,
    val maxIdentifierLength: Int = 256,
    val circuitBreaker: CircuitBreaker?,
) {

  companion object {
    private val logger = LoggerFactory.getLogger(SluiceConfiguration::class.java)
    private const val DEFAULT_MAX_IDENTIFIER_LENGTH = 256

    @Suppress(
        "CyclomaticComplexMethod", "ThrowsCount") // linear validation chain, logic is easy-peasy
    fun from(config: ApplicationConfig): SluiceConfiguration {
      val exceptions = mutableListOf<ConfigurationException>()
      val redisUrl = config.propertyOrNull("rate-limit.backend.redis-uri")?.getString()
      val maxIdentifierLength =
          config.propertyOrNull("rate-limit.validation.max-identifier-length")?.getString()?.toInt()
              ?: DEFAULT_MAX_IDENTIFIER_LENGTH
      val policiesLocation =
          config.propertyOrNull("rate-limit.policies.location")?.getString() ?: ""

      val rawThreshold = config.propertyOrNull("rate-limit.circuit-breaker.threshold")?.getString()
      val rawTimeout = config.propertyOrNull("rate-limit.circuit-breaker.timeout-ms")?.getString()

      val threshold =
          rawThreshold?.let {
            it.toIntOrNull()
                ?: throw ConfigurationException(
                    "rate-limit.circuit-breaker.threshold must be a valid integer, got: '$it'")
          }
      val timeout =
          rawTimeout?.let {
            it.toIntOrNull()
                ?: throw ConfigurationException(
                    "rate-limit.circuit-breaker.timeout-ms must be a valid integer, got: '$it'")
          }
      val circuitBreaker =
          when {
            threshold != null && timeout != null -> CircuitBreaker(threshold, timeout.milliseconds)
            threshold == null && timeout == null -> null
            else ->
                throw ConfigurationException(
                    "rate-limit.circuit-breaker requires both failure-threshold and timeout-ms, or neither")
          }

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

      return SluiceConfiguration(policiesLocation, redisUrl, maxIdentifierLength, circuitBreaker)
    }
  }
}
