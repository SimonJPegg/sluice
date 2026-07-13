-- Sliding window counter: weighted approximation across two fixed windows
-- KEYS[1] = rate limit key (Redis hash)
-- ARGV[1] = limit (max requests per window)
-- ARGV[2] = window duration in seconds
-- Returns: {current_count_after_increment, ttl_remaining}
--   If denied, current_count_after_increment > limit

local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

-- Get current state from hash
local current_count = tonumber(redis.call('HGET', key, 'current') or 0)
local previous_count = tonumber(redis.call('HGET', key, 'previous') or 0)
local window_start = tonumber(redis.call('HGET', key, 'start') or 0)

-- Use Redis server time for consistency across replicas
local time = redis.call('TIME')
local now = tonumber(time[1])

-- First request — initialise
if window_start == 0 then
  redis.call('HSET', key, 'current', 1, 'previous', 0, 'start', now)
  redis.call('EXPIRE', key, window * 2)
  return {1, window}
end

local elapsed = now - window_start

-- Two or more windows stale — discard everything
if elapsed >= (window * 2) then
  redis.call('HSET', key, 'current', 1, 'previous', 0, 'start', now)
  redis.call('EXPIRE', key, window * 2)
  return {1, window}
end

-- One window expired — rotate
if elapsed >= window then
  previous_count = current_count
  current_count = 0
  window_start = window_start + window
  elapsed = now - window_start
  redis.call('HSET', key, 'current', 0, 'previous', previous_count, 'start', window_start)
  redis.call('EXPIRE', key, window * 2)
end

local remaining = window - elapsed
local overlap = remaining / window
local estimated = math.floor(previous_count * overlap) + current_count

-- Check limit before incrementing
if estimated + 1 > limit then
  return {estimated + 1, remaining}
end

-- Under limit — increment and allow
current_count = current_count + 1
redis.call('HSET', key, 'current', current_count)
redis.call('EXPIRE', key, window * 2)

return {current_count + math.floor(previous_count * overlap), remaining}
