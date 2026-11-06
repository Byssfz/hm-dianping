# 秒杀订单可靠投递

秒杀 Lua 在新 Stream `stream.orders.reliable.v1` 写入订单意图、预扣 Redis 库存并记录购买用户及订单级预扣标记。Redis 单线程脚本隔离执行，但脚本异常不会回滚已完成的写入；异常产生的不完整 intent 保留并记录日志，需人工对账，不会被投递或自动回补。旧 `stream.orders` 不自动重放，历史积压须另行核对。

后台 `SeckillOutboxRelay` 每次读取最多 100 条，默认间隔 5 秒，可用 `hmdp.seckill-outbox.interval-ms` 配置。它校验 Redis 预扣标记，再把订单发送到原有 RocketMQ topic `hmseckillTopic`；无论发送成功、失败或超时都保留 intent，直到 MySQL 查到同订单 ID、用户和券一致后才删除。SQL 或 MQ 故障期间保留并重试；永久异常通过日志和 Stream 保留以供人工检查，不得仅凭未找到订单盲目回补库存。发送可能重复，消费端同 ID 同用户同券按成功处理（包含已关订单）；不同订单冲突、库存不足或用户锁忙抛异常交由 RocketMQ 重试。未处理的长期记录应监控和人工处理。

秒杀请求返回的订单 ID 是字符串，避免浏览器丢失 64 位精度。登录用户可查询 `GET /voucher-order/{id}/status`。仅允许查询本人订单或本人预扣记录；`data.orderId` 是字符串，`state` 为 `PENDING` 或 `CREATED`，后者 `orderStatus` 为数据库状态。返回 PENDING 仅代表存在本人预扣标记，并不保证最终下单成功；此前旧标记不一定有新 Stream 记录。

修正订单 ID 跨日重复问题：新 ID 使用 UTC 秒与按 UTC 日期分组的 Redis 递增计数，隔离旧版计数键并避开旧版时间戳空间。Redis 计数数据丢失、时钟回拨、Redis 写入/持久化故障等仍可能破坏唯一性或可靠性；本方案依赖单实例 Redis 和持久化，不能保证跨系统精确一次。旧运行实例和新实例混跑时，旧实例仍发送旧消息且旧 Stream 不会重放，应避免混跑并明确切换边界。Redis Stream/标记长期增长须监控；没有实现自动安全回补、死信补偿、支付或全量历史修复。

Redisson 使用 `spring.redis` 的主机、端口、库、密码和 SSL 设置，与 RedisTemplate 保持一致。

Java 8 定向测试：`mvn '-Dtest=RedisIdWorkerTest,SeckillOutboxRelayTest,OrderStatusTest,SeckillConsumerTest,RedissonConfigTest' test`。单测模拟 Redis、MySQL、RocketMQ；真实 Redis 对正常预扣、重复请求、非法库存输入做过隔离验证。另在临时隔离 MySQL、Redis 和 RocketMQ 中验证真实下单、重复消息只扣一次库存，以及 Broker 暂停期间 intent 保留、恢复后最终落库；临时服务已清理。该联调不是生产可靠性或性能压测证明。
