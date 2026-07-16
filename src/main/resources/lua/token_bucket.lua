-- Token bucket: lazy refill based on elapsed time, consume one per request
-- KEYS[1] = rate limit key (Redis hash)
-- ARGV[1] = limit (bucket capacity)
-- ARGV[2] = window duration in seconds (time to refill from empty to full)
-- Returns: {allowed (1/0), tokens_remaining, ttl}

local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

-- Derive refill rate
local refill_rate = limit / window
-- Get current time from Redis
local time = redis.call('TIME')
local now = tonumber(time[1]) + tonumber(time[2]) / 1000000
-- Read stored state from hash (tokens + last refill time)
local tokens = tonumber(redis.call('HGET', key, 'tokens') or limit)
local last_refill = tonumber(redis.call('HGET', key, 'last_refill') or now)
-- Calculate elapsed time since last refill (in seconds, with fractional precision)
local elapsed = now - last_refill
-- Calculate tokens released and cap at limit
local released = elapsed  * refill_rate
local current = math.min(tokens + released,limit)

-- decide
local allowed = 0
local count = current
local ttl_ms = math.floor(((1 - current) / refill_rate) * 1000)
if current >= 1 then
  allowed = 1
  count = current -1
  ttl_ms = math.floor((limit - (current -1)) / refill_rate * 1000)
end
redis.call('HSET', key, 'tokens', count, 'last_refill', now)
redis.call('EXPIRE', key, window * 2)
return {allowed, count, ttl_ms}
