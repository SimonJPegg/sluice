# 005 — Logging Levels

## Context

Sluice handles lots of rate limit checks per second. Each request is a simple
yes/no decision. Logging every request at INFO would generate too much noise and increase latency.

Metrics already cover "is it working?", Logs cover "what went wrong?" and "what's happening during development?"

Logback as the implementation — it's Ktor's default and documented choice. 

## Decision

Two log levels on the hot path:

- **debug** — request received, policy resolved, evaluation result, Redis round-trip timing.
  Off in production. Used during local development and when diagnosing issues.
- **error** — Redis connection failures, script load failures, config validation errors,
  unrecoverable states. Things that need human attention.

**info** is reserved for lifecycle events only: startup ("loaded N policies, connected to Redis"),
shutdown, config reload (if ever added). Never on the hot path.

Production default log level: **error**.

## Consequences

- Debugging production issues requires either temporarily bumping the log level to debug
  or relying on metrics and response headers. Acceptable — metrics tell you something's
  wrong, a brief level bump tells you why.
- No warn level in use. If we find a legitimate middle ground later (e.g. fail-open triggered
  but service still functioning), we can introduce it then. 
- Per-request debug logging means local development gives full visibility.
