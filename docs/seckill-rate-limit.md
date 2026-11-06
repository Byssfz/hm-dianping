# 秒杀接口 Redis + AOP 滑动窗口限流

`POST /voucher-order/seckill/{id}` 的 Controller 由 Spring AOP 拦截，在调用秒杀服务前执行 Redis Lua 脚本。保留原有 RocketMQ 下单流程，限流不修改库存或订单。其他接口不受此规则影响。

## 规则

- 以 Redis 服务器时间计算过去一个窗口 `(当前时间 - 窗口, 当前时间]` 的已放行请求。
- 两个维度同时检查：所有秒杀券共享的接口总请求数、当前登录用户对所有秒杀券的请求数。均只统计已放行请求；任一维度超限时不记录本次请求，返回 `Result.fail("请求过于频繁，请稍后重试")`，不会调用下单服务。
- Lua 中使用两个有序集合，清理窗口外成员后检查数量；采用递增序列作为成员，避免同一毫秒的请求互相覆盖。检查、计数和过期设置在同一脚本完成。所有键带相同的 `{seckill}` 哈希标签以支持 Redis Cluster 同槽执行；空闲后键会过期。
- 已认证用户 ID 来自 `UserHolder`，不采用客户端传入的 ID。用户未登录时返回请先登录；平常 MVC 登录拦截器会先处理登录。

参数可通过 Spring 属性、环境变量或命令行覆盖，默认值为：

| Spring 属性 | 环境变量 | 默认值 |
| --- | --- | --- |
| `hmdp.rate-limit.seckill.window-millis` | `HMDP_RATE_LIMIT_SECKILL_WINDOW_MILLIS` | 10000 毫秒 |
| `hmdp.rate-limit.seckill.global-limit` | `HMDP_RATE_LIMIT_SECKILL_GLOBAL_LIMIT` | 1000 次/窗口 |
| `hmdp.rate-limit.seckill.user-limit` | `HMDP_RATE_LIMIT_SECKILL_USER_LIMIT` | 10 次/窗口 |

所有值必须为正数；启动时校验。默认值只是初始配置，须结合实际负载调整。这个机制是应用层请求准入，不保证在流量抵达应用前过滤攻击，也不解决 Redis 库存和 RocketMQ 消息一致性。

## 故障处理

Redis 脚本抛出异常或返回无效值时记录告警并拒绝当前秒杀请求，响应“限流服务暂时不可用，请稍后重试”，不会进入库存扣减。其他接口无此切面。返回采用项目现有 `Result` 格式，不额外设置 HTTP 429。限流拒绝的请求不占用两个维度配额；通过限流后即使业务失败，本次请求仍计入窗口。

## 验证

```powershell
mvn '-Dtest=SeckillRateLimitAspectTest' test
```

测试启动精简 Spring 容器并通过 Controller 的真实 AOP 代理调用，Redis 和秒杀服务使用 mock，验证放行、两个维度超限、Redis 异常和无效结果、缺少身份与配置校验。在 Java 8 下 5 项通过，其中包括环境变量覆盖默认配置。另使用本机真实 Redis（127.0.0.1:16379）和 UUID 隔离键运行以下验证，覆盖两个限流维度、窗口过期恢复、96 个并发请求以及同毫秒请求不丢计数；执行后精确删除本次临时键：

```powershell
python docs/verify-seckill-rate-limit.py src/main/resources/seckill-rate-limit.lua
```

这两类验证不等同于完整 HTTP、RocketMQ 链路或压测。
