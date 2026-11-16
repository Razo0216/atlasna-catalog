-- Single use of a refresh token, atomically (two parallel requests with the same token can't both succeed).
--
-- KEYS[1]  atlasna:v1:refresh:{hash}        the live token → "userId|familyId"
-- KEYS[2]  atlasna:v1:refresh-used:{hash}   remembers a consumed token until its original expiry
-- KEYS[3]  atlasna:v1:refresh-grace:{hash}  a just-consumed token may be presented ONE more time, for ARGV[1] ms
-- ARGV[1]  grace window in milliseconds (0 = no grace)
--
-- Returns { "OK", value }      the token was live: it is now consumed and remembered as used
--         { "GRACE", value }   consumed seconds ago and presented again once (the response with its successor was
--                              lost: page reloaded, network dropped) → the caller replaces the successor, no revocation
--         { "REUSED", value }  the token was already consumed: someone holds a copy → revoke its family
--         { "INVALID", "" }    unknown, expired or revoked

local value = redis.call('GET', KEYS[1])
if value then
  local ttl = redis.call('PTTL', KEYS[1])
  redis.call('DEL', KEYS[1])
  if ttl > 0 then
    redis.call('SET', KEYS[2], value, 'PX', ttl)
  end
  local grace = tonumber(ARGV[1])
  if grace and grace > 0 then
    redis.call('SET', KEYS[3], value, 'PX', grace)
  end
  return { 'OK', value }
end
local graced = redis.call('GET', KEYS[3])
if graced then
  redis.call('DEL', KEYS[3]) -- only once: a third presentation is reuse
  return { 'GRACE', graced }
end
local used = redis.call('GET', KEYS[2])
if used then
  return { 'REUSED', used }
end
return { 'INVALID', '' }
