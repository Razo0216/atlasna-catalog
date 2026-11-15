-- Token bucket, atomic (Redis runs a script without interleaving other commands, so parallel requests can't both
-- take the last token). Time comes from the Redis server, so every app instance agrees on it.
--
-- KEYS[1]  bucket key (hash: tokens, ts)
-- ARGV[1]  capacity       max tokens (= burst size)
-- ARGV[2]  period_ms      time to refill a full bucket (rate = capacity / period)
-- ARGV[3]  cost           tokens to take: 1 = consume, 0 = only check
--
-- Returns { allowed (1|0), retry_after_ms }

local capacity = tonumber(ARGV[1])
local period = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])
local rate = capacity / period -- tokens per millisecond

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end
tokens = math.min(capacity, tokens + math.max(0, now - ts) * rate)

local needed = math.max(cost, 1)
if tokens >= needed then
  if cost > 0 then
    tokens = tokens - cost
    redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
    -- The key disappears once the bucket would be full again: nothing to keep for idle subjects.
    redis.call('PEXPIRE', KEYS[1], math.ceil((capacity - tokens) / rate) + 1000)
  end
  return { 1, 0 }
end
return { 0, math.ceil((needed - tokens) / rate) }
