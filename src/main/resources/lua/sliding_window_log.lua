-- Sliding window log: sorted set of timestamps, precise counting
-- KEYS[1] = rate limit key (sorted set)
-- ARGV[1] = limit (max requests per window)
-- ARGV[2] = window duration in seconds
-- Returns: {allowed (1/0), count, ttl_remaining}

local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

-- Get "now" from Redis (TIME)
local time = redis.call('TIME')
local now = tonumber(time[1])

-- Prune old entries
local window_started = now - window
redis.call('ZREMRANGEBYSCORE', key, '-inf', window_started)

-- Count what's left
local usage = tonumber(redis.call('ZCARD', key))

-- Get ttl from oldest entry
local ttl = window
if usage > 0 then
  local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
  ttl = tonumber(oldest[2]) + window - now
end

-- If under limit: add, set expiry, return allowed
if usage + 1 <= limit then
  local member = now .. ':' .. usage
  redis.call('ZADD', key, now, member)
  redis.call('EXPIRE', key, window)
  return {1, usage + 1, ttl}
end

-- At limit: return denied
return {0, usage, ttl}
