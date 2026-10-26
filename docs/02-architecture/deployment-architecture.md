# 部署拓扑与环境边界（Proposed）

## 环境矩阵
| 环境 | 基础设施 | 安全 Profile | 目的 |
|---|---|---|---|
| Local | 单 JVM Memory 用于最小 RPC smoke；多 JVM 隔离本地 Nacos | `TRUSTED_DEVELOPMENT` 仅可信隔离 | 开发速度；不得公网开放 |
| Test/Staging | MySQL、独立 Nacos、多个后端实例 | `NETWORK_MTLS` | 真正的故障恢复、授权、兼容性 |
| Demo | 可信主机上的 Compose，受保护 Nacos、限额 Demo Handler | `NETWORK_MTLS` | HTTPS 公网可访问的有限功能样例 |
| Production | 多节点，独立 DB/Nacos 备份、观测系统 | `NETWORK_MTLS` | 实际 SLA 必须基于部署条件验收 |

```mermaid
flowchart TB
  Internet[Public Internet] --> Domain[DNS]
  Domain --> HTTPS[Reverse Proxy HTTPS]
  HTTPS --> Frontend[Static Vue Frontend]
  HTTPS --> API[flow-api REST]
  subgraph PrivateNet[Private Service Network]
    API --> Backend[OTRYX RPC Apps]
    Backend --> Worker[Worker Consumer Processes]
    Backend --> Nacos[(Nacos 3.2.4 (isolated development))]
    Backend --> DB[(MySQL)]
    Backend --> OBS[Telemetry]
  end
  DB --> Backup[Encrypted Backup]
```

## 网络/端口原则
不在公网暴露 MySQL、Nacos、RPC Listener、监控内部接口和 Worker 管理 RPC；公网只允许反向代理 HTTPS。端口数值由 M1 Compose 和环境模板实测后确定，不在 M0 发明不存在的端口或配置。严格区分应用健康探针和业务服务就绪状态。

## 证书与身份
每个应用进程的叶证书到完整 `ApplicationKey(namespace, applicationName)` 需要真实指纹绑定，并配置授权。Registry 所有通道也要求保护；不能仅靠“内网”代替 mTLS。演示站点必须有证书过期告警和轮换演练。

## 发布和回滚
GHCR 镜像按 Commit SHA + 不可变 digest 标识；Staging 部署后验证 API、RPC/Nacos、数据库迁移、登录权限、内置任务执行；生产有审批。数据库迁移采取扩展-迁移-收敛，无法回滚的结构变更不得直接覆盖可恢复备份。单机 Demo 故障恢复不能被宣传为多节点 HA。

> **生产上线阻断**：目前 Nacos Adapter 不支持 `NETWORK_MTLS`。上述生产/演示拓扑仍属于 Proposed，而非已经验收的安全部署方案。有关唯一 Nacos 注册中心与 Bootstrap 详见 [Nacos 运维说明](../07-operations/nacos-registry.md)。
