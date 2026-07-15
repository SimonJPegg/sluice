-- Fixed window rate limiter: atomic increment with TTL management
-- KEYS[1] = rate limit key
-- ARGV[1] = limit (max requests per window)
-- ARGV[2] = window duration in seconds
-- Returns: {allowed (1/0), current_count, ttl_remaining}

local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

local count = redis.call('INCR', key)

-- First request in this window — set the expiry
if count == 1 then
  redis.call('EXPIRE', key, window)
end

local ttl = redis.call('TTL', key)

if count <= limit then
  return {1, count, ttl}
else
  return {0, count, ttl}
end
