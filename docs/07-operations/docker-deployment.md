# Docker Compose 部署拓扑（M2 开发配置）

项目根目录的 `compose.yaml` 是当前可执行候选；新 Nacos 方案需通过 Issue #15 的真实 CI 后才可声明验收。见 [Nacos 注册初始化](nacos-registry.md) 与 [启动步骤](m1-developer-compose.md)。

- **MySQL 8.4**：不同业务服务独立 Schema，开发账号共享；生产必须最小授权。
- **Nacos 3.2.4**：仅开发隔离网络 `nacos:8848`，Nacos gRPC 9848 不映射宿主机，数据卷持久化。
- **nacos-bootstrap**：一次性初始化 8 个 V2 Config Mapping 分片，任何 Provider/Consumer 启动前完成。
- **RPC 应用**：Metadata、Scheduler 两副本、Dispatcher 两副本、Engine、API；只有 `otryx-registry-nacos` 依赖，无 Etcd 双后端。
- **Web**：Vue/Nginx 仅宿主机回环 `127.0.0.1:8088`。

生产/公网部署当前受阻：Nacos Adapter 不支持 `NETWORK_MTLS` 的完整 Naming+Config+login 网络加密；开发配置关闭 Nacos 客户端鉴权且启用 `TRUSTED_DEVELOPMENT`，严禁暴露到外网。Worker SDK 实际执行属于 Issue #8，队列状态不能伪装为真实 Handler 执行。
