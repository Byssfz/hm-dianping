# 本机 MySQL、Redis、RocketMQ

统一配置在 docs/docker-compose-rocketmq.yml，保留原文件名和项目名，继续使用已有 Broker 数据卷。
Java 应用在宿主机 IDEA 中运行；application.yaml 当前地址已经匹配，无需修改。

## 启动

在项目根目录执行：

```powershell
docker compose -f docs/docker-compose-rocketmq.yml config --quiet
docker compose -f docs/docker-compose-rocketmq.yml up -d
docker compose -f docs/docker-compose-rocketmq.yml ps -a
docker compose -f docs/docker-compose-rocketmq.yml logs --tail 80 mysql namesrv broker init-topic
```

mysql、redis、namesrv、broker 应为 healthy；init-topic 应为 Exited (0)。
新 MySQL 会先导入数据，首次启动需要等待。状态检查不能代替应用真实收发验证。

| 服务 | 宿主机地址 | 连接配置 |
| --- | --- | --- |
| MySQL 8.0.32 | 127.0.0.1:13306 | 数据库 hmdp，root / 123456 |
| Redis 6.2 | 127.0.0.1:16379 | 无密码，默认 DB 0，开启 AOF |
| RocketMQ NameServer 4.9.7 | 127.0.0.1:9876 | 与 application.yaml 一致 |
| RocketMQ Broker | 127.0.0.1:10911、10909 | hmseckillTopic，4 个读/写队列 |

MySQL 使用 mysql_native_password 兼容项目当前 Connector/J 5.1.47。
所有端口只绑定本机。若其他容器已占用这些端口，需先处理冲突。
本配置创建独立 MySQL/Redis 数据卷，不会自动迁移旧容器数据。

## 初始化与持久化

- mysql-data：空卷首次启动时创建 hmdp 库，并导入 src/main/resources/db/hmdp.sql；已有数据库不会重复导入。该脚本包含 DROP TABLE，不要手工对已有业务数据库执行。
- redis-data：Redis 数据，开启 AOF 持久化。SQL 不会自动建立 Redis 秒杀库存等业务数据，需要按项目业务流程初始化。
- broker-store：沿用原 hmdp-rocketmq_broker-store 卷，保存消息与元数据。
- broker.conf 和 SQL 为只读挂载，路径以 Compose 文件目录为基准，缺少文件会报错。

## RocketMQ 低内存配置

| 进程 | 初始堆 | 最大堆 | 直接内存上限 |
| --- | --- | --- | --- |
| NameServer | 64MB | 128MB | 32MB |
| Broker | 128MB | 256MB | 64MB |
| 管理命令（健康检查、初始化） | 16MB | 64MB | 32MB |

这是单机少量消息的低内存起点，不是经过压测得出的绝对最低值。
两个常驻 JVM 最大堆合计 384MB，比原来的 768MB 减半；实际占用还包含元空间、线程栈、文件映射与缓存。
未给容器设置硬内存上限，避免把堆大小误当作总内存需求。出现 OOM 或频繁 GC 时先恢复 Broker 的 -Xmx512m、直接内存 256m。

Broker 将 G1 region 调到 1MB 并关闭 AlwaysPreTouch，适配小堆。
健康检查间隔调整为 30 秒。4.9.7 的 tools.sh 默认使用 1GB 堆且未追加 JAVA_OPT_EXT，因此健康检查和初始化直接调用 MQAdminStartup，显式限制为 64MB 堆。
保留现有 4 个队列和 128MiB CommitLog 文件大小，避免改变已有消息目录格式或缩减队列后遗漏历史消息；文件大小不是堆内存大小。

源码依据：[tools.sh](https://github.com/apache/rocketmq/blob/rocketmq-all-4.9.7/distribution/bin/tools.sh)、[runbroker.sh](https://github.com/apache/rocketmq/blob/rocketmq-all-4.9.7/distribution/bin/runbroker.sh)。

检查实际内存：

```powershell
docker stats --no-stream
```

## 路由检查

```powershell
docker compose -f docs/docker-compose-rocketmq.yml exec broker sh -c 'java -Xms16m -Xmx64m -XX:MaxDirectMemorySize=32m -cp "$ROCKETMQ_HOME/conf:$ROCKETMQ_HOME/lib/*" org.apache.rocketmq.tools.command.MQAdminStartup topicRoute -n namesrv:9876 -t hmseckillTopic'
docker compose -f docs/docker-compose-rocketmq.yml run --rm init-topic
```

路由应包含 hmdp-broker 和 127.0.0.1:10911。
Broker 在容器内通过 namesrv:9876 注册，但向宿主机 Java 客户端公布 127.0.0.1。
应用改为容器或远程部署时，需要一起调整地址。

## 停止

```powershell
docker compose -f docs/docker-compose-rocketmq.yml down
```

该命令保留三个数据卷；加 -v 会删除数据库、Redis 和消息数据。


原 apache/rocketmq:4.9.8 标签在本次拉取时返回 not found，已改为查询确认存在的官方 4.9.7 标签。

broker-store-init 一次性修正消息卷根目录为镜像用户 3000:3000 所有，成功状态应为 Exited (0)；Broker 等它完成后启动。

## 旧 SQL 兼容与本次验证

首次导入由 docs/mysql/01-init.sh 执行：仅在导入会话中移除 NO_ZERO_DATE、NO_ZERO_IN_DATE，兼容原始 MySQL 5.6 SQL 的零日期默认值，不修改全局 sql_mode。
健康检查查询 SQL 最后创建的 tb_voucher_order，避免只因数据库接受连接就误报初始化成功。

2026-09-14：NameServer、Broker、Redis 启动健康，Topic 创建及路由检查通过。启动后空闲快照：NameServer 105.4MiB，Broker 177.6MiB，MySQL 379.8MiB，Redis 4.609MiB。该数值不是负载峰值或上限，业务消息收发尚未验证。
首次 MySQL 导入在零日期处中断，仅导入三张表；上述兼容脚本已补充，但未对这次部分初始化的数据卷重导入。重跑 up 不会自动重新初始化已有数据卷，需确认后修复。禁止把当前状态当作数据库已准备就绪。

## Docker cgroup v2 下消息拉取失败

本机 apache/rocketmq:4.9.7 镜像使用 OpenJDK 8u372。实测 StoreUtil 初始化时读取系统内存指标，会在 CgroupV2Subsystem.getInstance 抛出 NullPointerException，随后消息拉取持续报 Could not initialize class org.apache.rocketmq.store.StoreUtil。Broker 健康检查仍可能通过。

Compose 的 Broker JAVA_OPT_EXT 已增加 -XX:-UseContainerSupport，绕过该旧 JDK 的容器指标探测；显式配置的堆和直接内存上限仍保留，但 JVM 不再根据容器限制识别系统资源。修改后需重建 Broker（保留 broker-store 数据卷）：

```powershell
docker compose -f docs/docker-compose-rocketmq.yml up -d --no-deps broker
```

2026-09-14 验证：原积压的两条消息消费完成，券 10、12 的订单落库，数据库与 Redis 库存一致，consumerProgress 的 Diff Total 为 0。