-- Fixed window rate limiter: atomic increment with TTL management
-- KEYS[1] = rate limit key
-- ARGV[1] = limit (max requests per window)
-- ARGV[2] = window duration in seconds
-- Returns: {current_count, ttl_remaining}

local count = redis.call('INCR', KEYS[1])

-- First request in this window — set the expiry
if count == 1 then
  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
end

local ttl = redis.call('TTL', KEYS[1])

return {count, ttl}
