# 商铺详情单实例多级缓存

## 适用范围和查询流程

商铺详情 `ShopServiceImpl.queryByID` 使用 Caffeine、Redis 和 MySQL 依次查询。Caffeine 按商铺 ID 存储存在的商铺；命中后直接返回，否则进入原有 Redis 缓存穿透查询。Redis 命中商铺后回填 Caffeine；Redis 未命中时查询 MySQL，并将结果写入 Redis。数据库中不存在的商铺只在 Redis 缓存空值，不写入 Caffeine。返回商铺时复制对象，避免调用方修改本地缓存中的对象。

| 缓存 | 内容和键 | 过期策略 |
| --- | --- | --- |
| Caffeine | 商铺 ID → 商铺对象 | 最多 1,000 条，写入后 30 秒过期 |
| Redis | `cache:shop:{id}` → 商铺 JSON | 30 分钟 |
| Redis | `cache:shop:{id}` → 空值 | 2 分钟 |

Caffeine 配置位于 `ShopCacheConfig`，Redis 键前缀和时间位于 `RedisConstants`。当前逻辑过期查询未接入商铺详情，不属于这条查询链路。

## 商铺更新和一致性边界

`ShopServiceImpl.update` 在数据库更新成功后注册事务回调；事务提交后先删除 Redis 中的对应键，再清理本地 Caffeine。回滚时不会执行提交回调。Redis 删除抛出异常时记录错误，并在 `finally` 中清理本地缓存。

这是单实例缓存方案，并未实现跨实例本地缓存失效。并发读取可能先读到旧数据，在更新和缓存失效后再回填旧值；如果 Redis 删除失败，后续请求也可能重新读到 Redis 里的旧值。过期时间有助于减少旧值存留，但不代表数据库与两级缓存强一致，也不能仅凭本地 30 秒 TTL 推断所有场景的数据延迟上限。当前没有为 Redis 删除失败实现可靠重试或补偿。

新增商铺的现有流程不会主动删除同 ID 的 Redis 空值缓存。如果该 ID 之前已经被查询并缓存为空，新商铺的详情可能要等空值缓存过期后才能查到。

## 验证

使用 Java 8 执行定向测试：

```powershell
mvn '-Dtest=ShopMultiLevelCacheTest,CustomerServiceToolsTest' test
```

本次执行共 16 项通过，其中 9 项商铺缓存测试覆盖本地命中、Redis 和数据库回填、空值缓存、本地过期、提交和回滚回调、删除失败、更新失败以及返回对象隔离。Redis、数据库和事务回调在这些测试中使用模拟或手动触发；另外 7 项为客服工具的既有测试。

另使用真实 Redis 和随机独立前缀临时键验证了 `CacheClient` 的正常值与空值回源、二次命中及不同 TTL；测试后精确删除了本次创建的键。此验证不等同于完整 Spring 应用、真实数据库事务和 HTTP 接口联调。尚未进行吞吐量或延迟压测，不能据此量化性能提升。
