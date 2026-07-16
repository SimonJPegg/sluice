# 003 — Lua script return contract: explicit allowed flag

**Date:** 2026-07-15

## Context

Original Lua scripts returned `{count, ttl}`. Kotlin derived allow/deny by checking `count <= limit`. Worked fine for fixed window because `INCR` always increments — even past the limit — so the count naturally exceeds it.

Sliding window log broke this. It doesn't add an entry when denied (you don't want rejected requests polluting the sorted set). So the count stays at exactly the limit on denial, `count <= limit` is true, and Kotlin allows a request that should be denied.

Options I looked at:

1. **Return `count + 1` on the deny path** — lies about what's in the set. Debugging nightmare.
2. **Change Kotlin to `count < limit`** — breaks fixed window where count == limit is the last valid request.
3. **Add always, remove if over limit** — consistent with fixed window, but pointless writes for denied requests.
4. **Return an explicit flag** — the script already knows the answer. Just say it.

## Decision

All Lua scripts return `{allowed (1/0), count, ttl}`. Kotlin reads `result[0]` as the verdict. No re-derivation.

The script owns the decision. Kotlin is a dumb consumer of it.

## Consequences

All three scripts updated at once. Token bucket will follow the same shape.

`RedisAlgorithm.calculate` is simpler — reads a flag instead of re-running limit logic. Adding a new algorithm just means returning the same 3-element array.

The `count` field is now informational (drives `remaining` in the response header). If a script returns wrong count but correct flag, the decision is still right — just the `X-RateLimit-Remaining` header is off.

Token bucket overrides `calculate` because its `count` means "tokens remaining" not "requests made."
