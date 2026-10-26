# 生产与公网演示部署方案（Proposed）

## 公开站点目标
域名、DNS、HTTPS、统一入口、可操作安全 Demo、只读观测页面、版本标识、可追踪的部署 SHA；准确公网 URL 需在服务器、域名和 CI/CD 凭据准备后才能填写，当前没有真实 URL。

## 环境分层
`Local` 使用隔离开发安全 Profile；`Staging` 用真实 Etcd/TLS、独立 DB、两个以上业务节点；`Demo` 受限可公网访问，不运行任意用户代码；`Production` 的 SLO 要根据多机部署和灾备演练制定。

## CI/CD（计划）
PR 检查 → Merge main → Java/前端构建 → 镜像扫描/SBOM → GHCR digest → 部署 Staging → 真实 RPC & Playwright E2E → 人工批准 → 发布 Demo/Production → 业务 Smoke → 保留可回滚版本。PR 阶段采用路径感知的轻量门禁；故障与容量矩阵属于独立发布验收，不要在每次小 PR 上重复跑完整耗时矩阵。

## 安全必须满足
1. 外部网络仅 443/HTTPS，内网 RPC、Etcd、MySQL、Prometheus 不直曝公网。
2. 网络部署 `NETWORK_MTLS`，证书绑定到完整应用标识，Provider 显式方法授权；Etcd ACL/TLS 和 Codec 验收必须真实通过。
3. Demo 使用专用账号/权限和项目数据，每日/定期重置。禁止任意 Shell/SQL、Webhook/出网 URL、上传 Jar 或脚本执行。
4. CSRF、Rate Limit、并发/积压/日志/磁盘与 CPU 额度、凭证轮换、审计和异常告警完整。
5. MySQL/Etcd 备份恢复、数据库迁移回滚边界已演练。

## 生产上线/回滚判断
镜像必须可用 digest 回溯；新旧版本滚动兼容 Run/Revision Schema；部署失败先阻断新调度，再回滚服务镜像并进行运行状态对账；不可逆数据库迁移要按扩展→迁移→收敛拆阶段。公开站点出现权限绕过或远程代码执行可能时必须立即关闭演示任务入口。

## 待准备外部资源
GitHub 新仓库、服务器/托管平台、域名和 DNS 管理、TLS/Secrets、GHCR 发布权限、Staging 测试资源和账单预算。无这些真实资源时不能声称站点已部署。
