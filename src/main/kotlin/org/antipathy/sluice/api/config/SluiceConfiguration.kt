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
    val redisUri: RedisURI?,
    val maxIdentifierLength: Int = 256,
    val maxConcurrentRequests: Int?,
    val circuitBreaker: CircuitBreaker?,
    val apiKey: String?,
) {

  companion object {
    private val logger = LoggerFactory.getLogger(SluiceConfiguration::class.java)
    private const val DEFAULT_MAX_IDENTIFIER_LENGTH = 256
    private const val DEFAULT_COMMAND_TIMEOUT_MS = 200L

    /** build our internal config from ktor's */
    fun from(config: ApplicationConfig): SluiceConfiguration {
      val exceptions = mutableListOf<ConfigurationException>()

      val redisUri = parseRedisUri(config, exceptions)
      val policiesLocation = parsePoliciesLocation(config, exceptions)
      val maxIdentifierLength = parseMaxIdentifierLength(config, exceptions)
      val maxConcurrentRequests = parseMaxConcurrentRequests(config)
      val circuitBreaker = parseCircuitBreaker(config)
      val apiKey = parseApiKey(config)

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
          redisUri,
          maxIdentifierLength,
          maxConcurrentRequests,
          circuitBreaker,
          apiKey,
      )
    }

    private fun parseApiKey(config: ApplicationConfig): String? {
      return config.propertyOrNull("rate-limit.auth.api-key")?.getString()
    }

    private fun parseRedisUri(
        config: ApplicationConfig,
        exceptions: MutableList<ConfigurationException>,
    ): RedisURI? {
      val raw = config.propertyOrNull("rate-limit.backend.redis-uri")?.getString()
      if (raw.isNullOrBlank()) return null

      val uri =
          try {
            RedisURI.create(raw)
          } catch (e: IllegalArgumentException) {
            exceptions.add(ConfigurationException("invalid Redis URI: ${e.message}"))
            null
          }

      uri?.timeout = java.time.Duration.ofMillis(parseCommandTimeoutMs(config, exceptions))
      return uri
    }

    private fun parseCommandTimeoutMs(
        config: ApplicationConfig,
        exceptions: MutableList<ConfigurationException>,
    ): Long {
      val raw =
          config.propertyOrNull("rate-limit.backend.command-timeout-ms")?.getString()
              ?: return DEFAULT_COMMAND_TIMEOUT_MS
      val value = raw.toLongOrNull()
      when {
        value == null ->
            exceptions.add(
                ConfigurationException(
                    "rate-limit.backend.command-timeout-ms must be a valid integer, got: '$raw'"
                )
            )
        value < 1 ->
            exceptions.add(
                ConfigurationException(
                    "rate-limit.backend.command-timeout-ms must be greater than 0, got: '$value'"
                )
            )
      }
      return value ?: DEFAULT_COMMAND_TIMEOUT_MS
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
