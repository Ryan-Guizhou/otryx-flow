# OTRYX Flow 验收计划与需求追踪矩阵（Proposed）

**状态：全部 `NOT_RUN`，当前是 M0 设计基线，不是验收结果。** 验收必须记录 Commit SHA、构建环境、测试命令、原始输出、截图/Trace/日志和审核人；不可凭 README 描述或绿色徽章替代实际业务证据。

## 进入验收前提
测试硬件、系统时间同步、MySQL/Etcd/证书版本、Worker Handler 负载类型、OTRYX RPC 锁定 SHA、数据种子和初始迁移状态必须先记录。`Local` 用于开发快捷验证，`Staging` 用于真实网络安全和并发/故障验收。发布前禁止仍使用 `TRUSTED_DEVELOPMENT` 的公网业务进程。

## 验收项与 FR/NFR 追踪
| 验收 ID | 关联需求 | 可观察的成功条件 | 当前结果 |
|---|---|---|---|
| AT-01 | FR-027, NFR-006 | 空环境部署数据库迁移和基础站点，重启后数据完整、健康探针就绪 | NOT_RUN |
| AT-02 | FR-001, FR-002, FR-003, FR-022, NFR-001, NFR-005, NFR-010 | 登录、角色隔离、跨项目越权均按权限拒绝并有审计 | NOT_RUN |
| AT-03 | FR-004, FR-005, FR-006, FR-008, FR-025, NFR-009 | 创建任务、发布 Revision、Cron 预览、人工触发、幂等键处理 | NOT_RUN |
| AT-04 | FR-005, FR-007, NFR-002 | 不同时区/DST/misfire 正确产生 UTC 火点并保留遗漏决策 | NOT_RUN |
| AT-05 | FR-009, FR-010, FR-011, FR-012, NFR-009 | 画布拒绝环、发布 R1、并行 DAG 后继正确等待、R2 不污染在途 R1 | NOT_RUN |
| AT-06 | FR-019, FR-026, NFR-002 | 数据库和服务重启后可查历史和未终态的权威执行状态 | NOT_RUN |
| AT-07 | FR-005, FR-007, FR-008, NFR-002, NFR-003 | 双 Scheduler 抢同一火点只出现一个逻辑 Run；集群接管可继续 | NOT_RUN |
| AT-08 | FR-015, FR-016, FR-017, NFR-002 | Worker A 断联，租约过期由 B 接管，容量不透支 | NOT_RUN |
| AT-09 | FR-006, FR-012, FR-013, FR-018, NFR-002 | 重放 Outbox/ACK 丢失和旧 Epoch 完成，Run/Node/Dispatch 最终权威状态不重复 | NOT_RUN |
| AT-10 | FR-013, FR-014, NFR-002 | 重试退避、不可重试失败、取消请求与实际终态区分 | NOT_RUN |
| AT-11 | FR-002, FR-024, FR-025, NFR-001 | 完整 mTLS 身份、授权、受保护 Etcd，伪造/过期证书/越权调用拒绝 | NOT_RUN |
| AT-12 | FR-019, FR-020, FR-021, FR-022, FR-023, FR-028, NFR-004, NFR-005, NFR-010 | 日志、Trace、指标、告警、审计按 Run 串联且无敏感数据 | NOT_RUN |
| AT-13 | FR-008, FR-015, FR-016, FR-017, NFR-003, NFR-007 | 固定机器下吞吐/积压/p99/内存有真实可重复记录，过载有界 | NOT_RUN |
| AT-14 | FR-004, FR-011, FR-025, NFR-006, NFR-008 | API/SDK/RPC 两版本滚动组合与回滚通过 | NOT_RUN |
| AT-15 | FR-026, FR-027, NFR-006 | 备份恢复和迁移重建定义/运行状态，恢复后可对账 | NOT_RUN |
| AT-16 | FR-001, FR-024, NFR-001, NFR-006 | HTTPS 站点可打开；受限访客安全演示，无任意代码入口 | NOT_RUN |
| AT-17 | FR-021, FR-023, FR-028, NFR-004 | Outbox 延迟、积压、Worker 失联/证书过期等告警能真实触发恢复 | NOT_RUN |

## 验收执行分组
- **基础 P0**：AT-01~AT-12、AT-14~AT-16，功能与安全的所有阻断项不允许豁免。
- **专项性能**：AT-13 使用 [受控性能计划](performance-plan.md) 预设可量化门槛；M0 尚无真实数据。
- **运维验证**：AT-17、备份与完整日志关联须在 Release Candidate 前通过。

## Release Gate
只有当 P0 全绿、无法恢复的非幂等场景已明确限制、生产证书配置真实验收、性能目标事先冻结且重复验证通过、部署与回滚演练完成，才允许将状态从 `Release Candidate` 修改为 `Ready to Publish`。任何证据必须同时指向版本 SHA 和执行环境；CI shared runner 不能当作物理机生产容量证明。
