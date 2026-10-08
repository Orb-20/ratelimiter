-- Atomic distributed token bucket.
-- Redis executes a script as a single unit, so no other command can run between the read and the write.
--
-- KEYS[1] = bucket key, e.g. rate-limit:token-bucket:user123  (Hash: tokens, lastRefill)
-- ARGV[1] = capacity            (max tokens)
-- ARGV[2] = refill rate         (tokens per second)
-- ARGV[3] = TTL                 (seconds of inactivity before the key is removed)
--
-- Returns {allowed (1/0), remaining whole tokens, retry-after in milliseconds}

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refillRate = tonumber(ARGV[2])
local ttlSeconds = tonumber(ARGV[3])

-- Use Redis server time (milliseconds) so all application servers share a single clock:
-- clock skew between app servers cannot hand out extra tokens.
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

-- 1-2. Read current state
local state = redis.call('HMGET', key, 'tokens', 'lastRefill')
local tokens = tonumber(state[1])
local lastRefill = tonumber(state[2])

-- Initialise a new bucket as full
if tokens == nil or lastRefill == nil then
    tokens = capacity
    lastRefill = now
end

-- 3-5. Lazy refill, capped at capacity
local elapsedMillis = math.max(0, now - lastRefill)
tokens = math.min(capacity, tokens + (elapsedMillis * refillRate / 1000))

-- 6-7. Consume one token if available
local allowed = 0
local retryAfterMillis = 0
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
else
    retryAfterMillis = math.ceil((1 - tokens) * 1000 / refillRate)
end

-- 8. Persist state and refresh the inactivity TTL
redis.call('HSET', key, 'tokens', tostring(tokens), 'lastRefill', tostring(now))
redis.call('EXPIRE', key, ttlSeconds)

-- 9. Integers only: Redis truncates Lua floats in replies anyway
return {allowed, math.floor(tokens), retryAfterMillis}
