# Docker Compose 部署蓝图（Proposed）

本文件是 Docker Compose 的**设计要求**，当前不包含 `compose.yaml`，不得声称 `docker compose up -d` 可以启动。

## Compose 应有的部署单元
- `reverse-proxy`：Caddy 或 Nginx，唯一公网入口，TLS 安全配置。
- `flow-web`：Vue 3 编译后静态内容；前端 API 地址通过可验证运行时参数或同域 `/api` 代理。
- `flow-api`, `flow-metadata`, `flow-scheduler`, `flow-engine`, `flow-dispatcher`, `flow-worker`：最小权限容器，资源预算、健康与就绪探针、不可变镜像 digest。
- `mysql`：有数据卷、备份、按服务 Schema/账户授权；迁移由部署任务控制而非每副本抢迁移。
- `etcd`：仅私有容器网络、TLS/RBAC、受控证书与数据持久化；服务启动前就绪。
- `otel-collector`, `prometheus`（可选集成部署）：只开私有端口或受鉴权运维入口。

## 最小环境变量和 Secret（命名提案）
`FLOW_PUBLIC_BASE_URL`, `FLOW_DB_HOST`, `FLOW_DB_USER`, `FLOW_DB_PASSWORD`, `FLOW_RPC_REGISTRY_ENDPOINTS`, `FLOW_TLS_CERT_PATH`, `FLOW_TLS_KEY_PATH`, `FLOW_TLS_CA_PATH`。名称尚未出现在程序里；不得把此表当成可运行 application.yml。密码和私钥不提交版本库，Compose secrets/外置环境注入。

## 启动依赖
数据库/Etcd 就绪与迁移通过 → 业务 Provider 安全配置加载 → 业务服务就绪 → API 可请求 → 前端可登录；Worker 未注册不能让静态站点/基础 API 假报调度服务完全健康。

## 单机演示局限
单机 Compose 只保证可重复演示和有限容灾演练，并非多节点高可用。数据库/Etcd 单点、宿主机宕机和网络断联均可影响服务；Production 的 HA 必须通过独立拓扑部署和实际故障验证。
