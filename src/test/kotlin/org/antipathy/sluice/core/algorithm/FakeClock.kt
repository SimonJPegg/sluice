package org.antipathy.sluice.core.algorithm

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

class FakeClock(var now: Instant = Instant.fromEpochSeconds(0)) : Clock {
  fun advance(duration: Duration) {
    now = now.plus(duration)
  }

  override fun now(): Instant = now
}
