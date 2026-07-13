# 002 — ConcurrentHashMap.compute over Mutex

**Date:** 2026-07-15

## Context

Need atomic read-modify-write per key. Two options:

Kotlin Mutex — coroutine-friendly but you need one per key (now you're managing a map of locks) or one global lock (now everything serialises). Both are worse than the problem.

ConcurrentHashMap.compute — atomic per-key out of the box. Can't suspend inside the lambda, but the lambda is arithmetic and a copy. 200ns blocking beats coroutine dispatch overhead.

## Decision

ConcurrentHashMap.compute. The lambda can't do anything async but it doesn't need to — there's no I/O in the in-memory path. Redis handles its own atomicity with Lua scripts, so this choice only affects in-memory.

## Consequences

Per-key atomicity without managing locks. One map, done.

The compute lambda returns the map value, not our domain response, so we smuggle the result out via a mutable var. It's ugly but it's one line and there's a comment. Could be removed by deriving the response from the counter state after compute returns — haven't done that yet.

Can't call suspend functions inside compute. Not a problem — the operation is CPU-bound. The sealed interface means we can swap implementations later without touching anything above.

200 coroutines hammering the same key in tests, exact allowed/denied counts. If this had a bug, those tests would flake.
