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
    val maxConcurrentRequests: Int?,
    val circuitBreaker: CircuitBreaker?,
) {

  companion object {
    private val logger = LoggerFactory.getLogger(SluiceConfiguration::class.java)
    private const val DEFAULT_MAX_IDENTIFIER_LENGTH = 256

    /** build our internal config from ktor's */
    fun from(config: ApplicationConfig): SluiceConfiguration {
      val exceptions = mutableListOf<ConfigurationException>()

      val redisUrl = parseRedisUrl(config, exceptions)
      val policiesLocation = parsePoliciesLocation(config, exceptions)
      val maxIdentifierLength = parseMaxIdentifierLength(config, exceptions)
      val maxConcurrentRequests = parseMaxConcurrentRequests(config)
      val circuitBreaker = parseCircuitBreaker(config)

      if (exceptions.isNotEmpty()) {
        logger.error("Configuration errors detected")
        val primary = exceptions.first()
        exceptions.drop(1).forEach { exception ->
          logger.error(exception.message)
          primary.addSuppressed(exception)
        }
        throw primary
      }

      return SluiceConfiguration(
          policiesLocation,
          redisUrl,
          maxIdentifierLength,
          maxConcurrentRequests,
          circuitBreaker,
      )
    }

    private fun parseRedisUrl(
        config: ApplicationConfig,
        exceptions: MutableList<ConfigurationException>,
    ): String? {
      val redisUrl = config.propertyOrNull("rate-limit.backend.redis-uri")?.getString()
      if (!redisUrl.isNullOrBlank()) {
        try {
          RedisURI.create(redisUrl)
        } catch (e: IllegalArgumentException) {
          exceptions.add(ConfigurationException("invalid Redis URI: ${e.message}"))
        }
      }
      return redisUrl
    }

    private fun parsePoliciesLocation(
        config: ApplicationConfig,
        exceptions: MutableList<ConfigurationException>,
    ): String {
      val policiesLocation =
          config.propertyOrNull("rate-limit.policies.location")?.getString() ?: ""
      if (policiesLocation.isBlank()) {
        exceptions.add(ConfigurationException("policy location is empty"))
      } else if (!Paths.get(policiesLocation).exists()) {
        exceptions.add(ConfigurationException("policy location does not exist"))
      }
      return policiesLocation
    }

    private fun parseMaxIdentifierLength(
        config: ApplicationConfig,
        exceptions: MutableList<ConfigurationException>,
    ): Int {
      val maxIdentifierLength =
          config.propertyOrNull("rate-limit.validation.max-identifier-length")?.getString()?.toInt()
              ?: DEFAULT_MAX_IDENTIFIER_LENGTH
      if (maxIdentifierLength < 1) {
        exceptions.add(ConfigurationException("max identifier length must be greater than 1"))
      }
      return maxIdentifierLength
    }

    private fun parseMaxConcurrentRequests(config: ApplicationConfig): Int? {
      val raw =
          config.propertyOrNull("rate-limit.max-concurrent-requests")?.getString() ?: return null
      val value =
          raw.toIntOrNull()
              ?: throw ConfigurationException(
                  "rate-limit.max-concurrent-requests must be a valid integer, got: '$raw'"
              )
      if (value < 1) {
        throw ConfigurationException(
            "rate-limit.max-concurrent-requests must be greater than 1, got: '$value'"
        )
      }
      return value
    }

    @Suppress(
        "ThrowsCount",
        "CyclomaticComplexMethod",
    ) // config validation, there's very little logic here
    private fun parseCircuitBreaker(config: ApplicationConfig): CircuitBreaker? {
      val rawThreshold = config.propertyOrNull("rate-limit.circuit-breaker.threshold")?.getString()
      val rawTimeout = config.propertyOrNull("rate-limit.circuit-breaker.timeout-ms")?.getString()

      val threshold = rawThreshold?.let {
        it.toIntOrNull()
            ?: throw ConfigurationException(
                "rate-limit.circuit-breaker.threshold must be a valid integer, got: '$it'"
            )
      }
      val timeout = rawTimeout?.let {
        it.toIntOrNull()
            ?: throw ConfigurationException(
                "rate-limit.circuit-breaker.timeout-ms must be a valid integer, got: '$it'"
            )
      }

      return when {
        threshold != null && timeout != null -> CircuitBreaker(threshold, timeout.milliseconds)
        threshold == null && timeout == null -> null
        else ->
            throw ConfigurationException(
                "rate-limit.circuit-breaker requires both failure-threshold and timeout-ms, or neither"
            )
      }
    }
  }
}
