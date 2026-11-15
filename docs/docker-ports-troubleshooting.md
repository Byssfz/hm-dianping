# Docker Desktop 重启后端口未恢复

2026-09-15 检查确认，`docker-compose-rocketmq.yml` 的 `ports` 配置有效。端口消失的原因是旧 `/mysql`、`/redis` 容器也绑定了 13306、16379，并设置 `restart=always`。

Docker Desktop 启动时旧容器先占用端口，项目容器恢复后只有 `HostConfig.PortBindings` 配置，运行时 `NetworkSettings.Ports` 为空。旧容器随后退出，Docker 也不会自动给已经运行的项目容器补发端口。

本机已执行以下命令关闭旧容器的自动重启：

```powershell
docker update --restart=no mysql redis
```

以后从项目根目录运行启动校验脚本：

```powershell
powershell -ExecutionPolicy Bypass -File docs/start-docker-services.ps1
```

脚本会完成以下检查：

1. 验证 Compose 配置可以解析。
2. 启动 MySQL、Redis、NameServer 和 Broker。
3. 等待四个服务达到 `healthy`。
4. 检查运行时端口映射是否为 13306、16379、9876、10909、10911。
5. 从 Windows 宿主机测试五个端口的 TCP 连通性。

如果又创建了绑定相同端口的其他容器，应先停止该容器或修改其宿主机端口。Compose 的 `restart: unless-stopped` 负责恢复容器进程，但无法解决外部容器造成的端口冲突。
