-- Single use of a refresh token, atomically (two parallel requests with the same token can't both succeed).
--
-- KEYS[1]  atlasna:v1:refresh:{hash}       the live token → "userId|familyId"
-- KEYS[2]  atlasna:v1:refresh-used:{hash}  remembers a consumed token until its original expiry
--
-- Returns { "OK", value }      the token was live: it is now consumed and remembered as used
--         { "REUSED", value }  the token was already consumed: someone holds a copy → revoke its family
--         { "INVALID", "" }    unknown, expired or revoked

local value = redis.call('GET', KEYS[1])
if value then
  local ttl = redis.call('PTTL', KEYS[1])
  redis.call('DEL', KEYS[1])
  if ttl > 0 then
    redis.call('SET', KEYS[2], value, 'PX', ttl)
  end
  return { 'OK', value }
end
local used = redis.call('GET', KEYS[2])
if used then
  return { 'REUSED', used }
end
return { 'INVALID', '' }
