-- Atomic sliding-window rate limiter.
-- KEYS[1] = ratelimit:{ip}:{endpoint}
-- ARGV[1] = current timestamp in milliseconds
-- ARGV[2] = window size in seconds
-- ARGV[3] = unique member id for this request
local key = KEYS[1]
local now = tonumber(ARGV[1])
local window_seconds = tonumber(ARGV[2])
local member = ARGV[3]
local window_ms = window_seconds * 1000

redis.call('ZREMRANGEBYSCORE', key, 0, now - window_ms)
redis.call('ZADD', key, now, member)
local count = redis.call('ZCARD', key)
redis.call('EXPIRE', key, window_seconds)

return count
