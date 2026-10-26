# 生产与公网演示部署方案（Proposed）

## 公开站点目标
域名、DNS、HTTPS、统一入口、可操作安全 Demo、只读观测页面、版本标识、可追踪的部署 SHA；准确公网 URL 需在服务器、域名和 CI/CD 凭据准备后才能填写，当前没有真实 URL。

## 环境分层
`Local` 使用隔离开发安全 Profile；`Staging` 计划独立 Nacos、DB、多个业务节点；当前受 Nacos Adapter 缺少 NETWORK_MTLS 阻断；`Demo` 受限可公网访问，不运行任意用户代码；`Production` 的 SLO 要根据多机部署和灾备演练制定。

## CI/CD（计划）
PR 检查 → Merge main → Java/前端构建 → 镜像扫描/SBOM → GHCR digest → 部署 Staging → 真实 RPC & Playwright E2E → 人工批准 → 发布 Demo/Production → 业务 Smoke → 保留可回滚版本。PR 阶段采用路径感知的轻量门禁；故障与容量矩阵属于独立发布验收，不要在每次小 PR 上重复跑完整耗时矩阵。

## 安全必须满足
1. 外部网络仅 443/HTTPS，内网 RPC、Nacos、MySQL、Prometheus 不直曝公网。
2. 网络部署 `NETWORK_MTLS`，证书绑定到完整应用标识，Provider 显式方法授权；当前 Nacos Adapter 不支持完整 NETWORK_MTLS；必须独立开发 Naming/Config/login 全链路保护并经端到端安全验收，不得仅靠单侧 TLS 宣称上线。
3. Demo 使用专用账号/权限和项目数据，每日/定期重置。禁止任意 Shell/SQL、Webhook/出网 URL、上传 Jar 或脚本执行。
4. CSRF、Rate Limit、并发/积压/日志/磁盘与 CPU 额度、凭证轮换、审计和异常告警完整。
5. MySQL/Nacos 备份恢复、数据库迁移回滚边界已演练。

## 生产上线/回滚判断
镜像必须可用 digest 回溯；新旧版本滚动兼容 Run/Revision Schema；部署失败先阻断新调度，再回滚服务镜像并进行运行状态对账；不可逆数据库迁移要按扩展→迁移→收敛拆阶段。公开站点出现权限绕过或远程代码执行可能时必须立即关闭演示任务入口。

## 待准备外部资源
GitHub 新仓库、服务器/托管平台、域名和 DNS 管理、TLS/Secrets、GHCR 发布权限、Staging 测试资源和账单预算。无这些真实资源时不能声称站点已部署。

## 当前注册中心生产门禁（Issue #15）

OTRYX RPC 固定 SHA 的 Nacos Adapter 仅支持 `TRUSTED_DEVELOPMENT`；完整 Naming/Config/login HTTP/gRPC mTLS 安全能力尚未实现。Nacos 3.2.4 当前 Compose 关闭鉴权且只运行私有开发网络，**不能部署到公网或正式生产环境**。生产接入之前须解决身份、ACL、证书、网络边界、密钥轮换、故障恢复和可靠备份，并通过真实 Linux 跨 JVM 验收。注册中心改成 Nacos 不改变这些安全阻断项。
