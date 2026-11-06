-- KEYS: global sorted set, user sorted set, sequence. Same Redis Cluster hash slot.
-- ARGV: window in milliseconds, global maximum, user maximum.
-- Redis time avoids application-node clock differences.
local now = redis.call('TIME')
local timestamp = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
local window = tonumber(ARGV[1])
local cutoff = timestamp - window
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', cutoff)
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', cutoff)
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then return 0 end
if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[3]) then return 2 end
-- A unique sequence avoids collapsing multiple requests in the same millisecond.
local sequence = redis.call('INCR', KEYS[3])
local member = tostring(sequence)
redis.call('ZADD', KEYS[1], timestamp, member)
redis.call('ZADD', KEYS[2], timestamp, member)
redis.call('PEXPIRE', KEYS[1], window)
redis.call('PEXPIRE', KEYS[2], window)
redis.call('PEXPIRE', KEYS[3], window)
return 1
