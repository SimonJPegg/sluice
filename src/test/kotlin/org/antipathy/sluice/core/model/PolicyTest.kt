package org.antipathy.sluice.core.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import org.antipathy.sluice.core.exceptions.InvalidPolicyConfigurationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PolicyTest {

  @Test
  fun `should create a valid policy`() {
    Policy("one", 100u, FailType.OPEN, 10.minutes, AlgorithmType.SLIDING_WINDOW_LOG)
  }

  @Test
  fun `should reject a blank policy id`() {
    assertThrows<InvalidPolicyConfigurationException> {
      Policy("", 100u, FailType.OPEN, 10.minutes, AlgorithmType.SLIDING_WINDOW_LOG)
    }
  }

  @Test
  fun `should reject a zero duration window`() {
    assertThrows<InvalidPolicyConfigurationException> {
      Policy("one", 100u, FailType.OPEN, Duration.ZERO, AlgorithmType.SLIDING_WINDOW_LOG)
    }
  }

  @Test
  fun `should reject a negative duration window`() {
    assertThrows<InvalidPolicyConfigurationException> {
      Policy("one", 100u, FailType.OPEN, (-10).minutes, AlgorithmType.SLIDING_WINDOW_LOG)
    }
  }
}
