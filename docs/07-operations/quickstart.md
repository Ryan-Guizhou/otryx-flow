# OTRYX Flow 本地开发快速启动（Nacos 3.2.4）

首次启动只需要 Docker Engine（含 Compose V2）和可访问 Maven/npm 镜像及 GitHub 的网络。启动脚本会生成本机随机凭据，构建 Java 服务与 Web 前端，按依赖顺序启动所有服务，并启动受限的示例 Worker：

```bash
./scripts/dev.sh up
```

打开 <http://127.0.0.1:8088>，用户名为 `admin`；首次生成的密码保存在仓库根目录 `.env`。脚本不会覆盖已有 `.env`，若文件里仍有 `REPLACE_WITH` 等模板值，会在构建前明确报错。生成的 `.env` 权限为 `0600`，不要提交该文件。

启动脚本等待 Nacos V2 Mapping 初始化成功后，再依次启动 Metadata、双 Scheduler、双 Dispatcher、Engine、API、Web 和示例 Worker。该 Compose 运行的是隔离开发配置，使用 MySQL 8.4、Nacos 3.2.4 与 OTRYX RPC；Nacos、数据库和 RPC 端口不映射到宿主机。

常用操作：

```bash
./scripts/dev.sh ps
./scripts/dev.sh logs flow-api
./scripts/dev.sh down
```

`down` 会停止容器并保留数据库卷；如需清空本地数据，确认不再需要后再手动执行 `docker compose down --volumes`。

## 可选观测栈

Prometheus、Grafana 和 OpenTelemetry Collector 不属于启动主链路，默认不会下载或启动。需要调试观测能力时，在主栈启动后另行执行：

```bash
docker compose -f compose.yaml -f compose.observability.yaml --profile worker-demo up -d
```

详细注册中心行为见 [Nacos bootstrap](nacos-registry.md)；Compose 网络、安全边界和排错见[开发部署说明](m1-developer-compose.md)。生产 mTLS、互联网部署和完整浏览器验收尚未完成，此开发配置不得暴露到不可信网络。
