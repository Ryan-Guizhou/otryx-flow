# 首次发行与上线 Checklist（Proposed）

- [ ] Repo 存在、Branch Protection/Required Checks/Issue/PR 模板可用。
- [ ] README 中文/英文事实与命令同步，Mermaid/本地链接全部可读。
- [ ] 锁定 OTRYX RPC Commit，发布物和 SBOM 可关联来源；未使用未经验证的 SNAPSHOT 动态更新。
- [ ] 前后端构建、静态检测、单测、数据库迁移、API Schema、两 JVM RPC E2E 全绿。
- [ ] `NETWORK_MTLS` 真正认证应用、受保护 Etcd、Provider AuthZ、证书轮换及到期警告通过。
- [ ] 至少一次执行、Outbox/Inbox、Lease/Fencing、非幂等恢复路径及取消竞态已覆盖。
- [ ] 全部 P0 `AT-*` 有准确 SHA/环境/原始日志/审核记录，无 Blocker。
- [ ] 性能门槛在测试前冻结，受控数据可复核；过载有界，不能以 runner smoke 宣称生产 SLO。
- [ ] GHCR 镜像有 digest、漏洞扫描、最小权限、非 root、配置与 Secret 分离。
- [ ] 数据迁移有备份/恢复演练、线上可回滚点、旧版本可读在途 Run。
- [ ] 公网网站仅 HTTPS，演示任务白名单、配额、无任意脚本执行入口。
- [ ] 授权、审计、日志脱敏、用户数据保留和许可证合规核查完成。
- [ ] 有演示站点 URL、健康检查、核心场景播放记录、故障告警联系渠道。
- [ ] 项目 License/品牌素材、Release Notes、源码包/构建产物与签名策略已审查。

**当前状态（2026-11-03）：** 清单仍全部未勾。隔离 Compose 只有 AT-15 有通过记录。配额、Handler 白名单和终态清理已有单元测试，但不能代替本清单。没有 admission 配置时仍能入队和领取，只说明当前 `1.0.0` 契约兼容旧行，不是 AT-14 的双版本滚动。`.github/workflows/m1-integration.yml` 仍手动调用超时、重试、数据库刷新、Collector 离线和下游导出失败脚本；本次没有重跑，AT-07 到 AT-11 保持 `NOT_RUN`。未打 Tag。
