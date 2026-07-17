package org.antipathy.sluice.core.policy

import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Test

class PolicyTest {

  @Test
  fun `should create a valid policy`() {
    Policy("one", 100u, FailType.OPEN, 10.minutes, AlgorithmType.SLIDING_WINDOW_LOG)
  }
}
