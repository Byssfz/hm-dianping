-- Single Redis instance only: existing stock keys do not share a cluster hash slot.
-- KEYS: stock, purchase set, recovery marker, reservation. ARGV: user id, voucher id.
if redis.call('EXISTS', KEYS[3]) == 1 then return 1 end
local stock = redis.call('GET', KEYS[1])
if not stock or not string.match(stock, '^%d+$') then return -1 end
if redis.call('PTTL', KEYS[1]) ~= -1 then return -1 end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) ~= 1 then return -1 end
if redis.call('GET', KEYS[4]) ~= ARGV[2] .. ':' .. ARGV[1] then return -1 end
local n = tonumber(stock)
-- Lua doubles exactly represent integers only up to 2^53; reject invalid stock.
if not n or n >= 9007199254740991 then return -1 end
-- MSET is one Redis command: stock and the idempotency marker change together.
redis.call('MSET', KEYS[1], string.format('%.0f', n + 1), KEYS[3], '1')
return 1
