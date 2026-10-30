# Nacos 3.2.4 唯一注册中心与独立 Bootstrap

> 当前本地开发 Compose 使用 Nacos Naming + Config V2 作为唯一应用级注册机制。Etcd 属于历史运行环境，不再作为当前兼容选项。

## 关键事实

- OTRYX RPC 固定源码 SHA：`502117d3301d78ea76a8d02b36e62585059d9902`。Adapter：`otryx-registry-nacos`；Server：`nacos/nacos-server:v3.2.4`。
- 统一 `namespace=public`、`nacos.group=OTRYX_RPC`、`nacos.cluster=DEFAULT`，Nacos 端点为 `nacos:8848`（容器内）或 `127.0.0.1:8848`（本地），**不含 URL 协议**。
- 按 **ApplicationKey + Instance** 注册而非接口级注册。SDK Naming 与 Config 协同实现应用成员、Manifest 和 Mapping。Nacos gRPC 9848 在私有容器网络内自动可达，不发布到公网。
- 固定 **8 个 Config Mapping 分片**。必须在业务 Provider/Consumer 写入前，用独立 JVM 完成 `NacosV2MetadataBootstrap`；它是部署阶段，不是应用生命周期回调。

## 本地启动

从仓库根目录执行：

```bash
./scripts/dev.sh up
```

脚本自动生成本地 `.env`，构建镜像，启动核心服务和示例 Worker。`start_dev_compose.sh` 先启动 MySQL 与 Nacos，并等待 `nacos-bootstrap` 的退出码为 0；再按 Metadata → Scheduler 两副本 → Dispatcher 两副本 → Engine → API → Web 顺序有界启动。普通 `docker compose up -d` 中 Java 服务也通过 `depends_on: service_completed_successfully` 等待 Bootstrap。站点地址为 <http://127.0.0.1:8088>。

## 一次性 Mapping 初始化

Compose 中的独立 `nacos-bootstrap` 使用项目后端镜像，调用 OTRYX RPC 已验证的命令行：

```text
java -Dloader.main=com.peachsoft.otryx.registry.nacos.NacosV2MetadataBootstrap
     -cp /apps/flow-api.jar
     org.springframework.boot.loader.launch.PropertiesLauncher
     nacos:8848 public OTRYX_RPC TRUSTED_DEVELOPMENT
```

上述多行仅示意 argv，实际完整命令见 `compose.yaml` 的 `entrypoint`/`command`。Bootstrap 保留已有合法分片、只填补缺失分片并核实远端 MD5。若 Bootstrap 失败，必须 fail closed；**不要跳过初始化或允许其与业务写入并发执行**。

## Etcd 迁移和故障恢复

1. 先停止旧的 Etcd 注册 Provider/Consumer 并备份 MySQL 业务数据；本变更不设计线上混合注册或 Etcd→Nacos 元数据复制。
2. 启用全新 Nacos scope，完成独占 Bootstrap。Etcd 的旧临时注册记录不能直接移植为可信 Nacos Manifest/ServiceMapping。
3. 严格按依赖顺序启动 JVM，执行真实 HTTP → Nacos → OTRYX RPC → MySQL 的业务验收；故障恢复、幂等/Lease Fencing 需要全量回归。
4. Nacos 不就绪或 Bootstrap 未完成：检查 `docker compose logs nacos nacos-bootstrap`，确认 HTTP readiness 和 Config MD5；不得绕过门禁。
5. 旧版本回滚需在业务进程停止后对齐正确的注册 scope，不可两种 Registry 并行混用；Nacos 的数据卷不能当普通缓存任意清空。

## 安全边界（上线阻断）

开发 Compose 将 `NACOS_AUTH_ENABLE=false`，且 OTRYX RPC 使用 `TRUSTED_DEVELOPMENT`，只运行在 `internal` 容器网络；**没有主机 Nacos HTTP/gRPC/Console 端口映射**。当前固定 Nacos Adapter 尚未实现完整 Naming/Config/login TLS/mTLS，`NETWORK_MTLS` 被明确拒绝。因此本配置不可公网、Staging/生产上线，必须在独立安全 Issue 中完成 TLS、身份、ACL 和跨进程验收。将 Nacos 放在 HTTPS 反代后面不能替代全链路支持。

依据：[OTRYX RPC Nacos V2 设计](https://github.com/Ryan-Guizhou/otryx-rpc/blob/502117d3301d78ea76a8d02b36e62585059d9902/docs/reference/registry-nacos-v2.md) 和 [Nacos 官方部署文档](https://www.nacos.io/docs/latest/manual/admin/deployment/deployment-standalone/)。
