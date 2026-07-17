# 004 — Policy Registry

**Date:** 2026-07-17

## Context

Policies are currently constructed in test code. Once the HTTP API exists, something needs to own "what policies are available?" — loading them, validating them, and serving them by ID at request time.

Options:

1. **YAML file loaded at startup.** Simple, declarative, lives in the repo or a ConfigMap. No runtime mutation.
2. **Database-backed with a CRUD API.** Allows runtime changes, but adds a Postgres dependency and a whole control plane. Way beyond v1.
3. **Environment variables.** Flat, no structure. Encoding a list of policies into env vars is painful.
4. **Hardcoded in code.** Defeats the point of a configurable service.

Duration format options:

- **ISO 8601 (`PT5M`, `PT1H`)** — Kotlin's `Duration.parseIsoString()` handles it natively. No custom parsing. Unambiguous.
- **Shorthand (`5m`, `1h`)** — Friendlier but requires a custom parser for zero benefit.

Deserialisation options:

- **yamlkt (kotlinx-serialization YAML)** — Pure Kotlin, multiplatform, stays in the kotlinx-serialization ecosystem already in the project. One library, one set of annotations.
- **Jackson + jackson-dataformat-yaml** — Heavier, second serialisation framework, Kotlin module has its own quirks.

## Decision

YAML file, loaded once at startup. ISO 8601 durations. yamlkt for deserialisation.

The registry is a read-only lookup: load, validate, serve. No hot-reload — if policies change, restart the service. That's fine for v1 and matches how ConfigMaps work in Kubernetes anyway (pod restart on change, or a sidecar that triggers restart).

All fields are required. No defaults. A rate limiter policy with an implicit `failType` is a bug waiting to happen at 2am.

Duplicate policy IDs are an error. Empty policy list is an error. If the file is missing or malformed, the service refuses to start with a specific message saying what's wrong.

The registry exposes an interface so the HTTP layer can be tested without touching YAML files.

## Consequences

Adding yamlkt as a dependency. It's pure Kotlin, actively maintained, and avoids pulling in a second serialisation framework.

Policies are static for the lifetime of the process. If we want runtime changes later, we swap the implementation behind the interface — nothing above changes.

Config errors surface at startup, not at request time. A deploy with a broken policy file fails immediately and rolls back. That's the correct behaviour.

ISO 8601 limits windows to time-based durations (seconds through hours). No calendar durations (months, years). That's fine — no sensible rate limit window is longer than a day, and `PT24H` covers that.
