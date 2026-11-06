local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local reservationKey = 'seckill:reservation:' .. orderId
local streamKey = 'stream.orders.reliable.v1'
local rawStock = redis.call('get', stockKey)
if not rawStock or not string.match(rawStock, '^%d+$') then return redis.error_reply('stock missing or invalid') end
local stock = tonumber(rawStock)
-- Match the database INT range and Redis integer syntax before writing an intent.
if not stock or stock > 2147483647 or rawStock ~= string.format('%.0f', stock) then
    return redis.error_reply('stock missing or invalid')
end
if stock <= 0 then return 1 end
local orderType = redis.call('type', orderKey).ok
local streamType = redis.call('type', streamKey).ok
if orderType ~= 'none' and orderType ~= 'set' then return redis.error_reply('invalid order set') end
if streamType ~= 'none' and streamType ~= 'stream' then return redis.error_reply('invalid outbox stream') end
if redis.call('exists', reservationKey) ~= 0 then return redis.error_reply('duplicate order id') end
if redis.call('sismember', orderKey, userId) == 1 then return 2 end
-- Record intent first: on a later script error an incomplete intent remains visible
-- for manual reconciliation, and is never sent without the matching reservation.
redis.call('xadd', streamKey, '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
redis.call('set', reservationKey, voucherId .. ':' .. userId)
return 0
