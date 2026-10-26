# 测试用例设计 Given / When / Then（Proposed）

| Case | 验收 | Given | When | Then |
|---|---|---|---|---|
| TC-001 | AT-02 | VIEWER 在项目 A，未知项目 B | 调 B 资源查询 | 403/404 统一遮蔽、不泄露 B 数据并记录审计 |
| TC-002 | AT-03 | 同一个手动触发幂等键 | 同时发送 10 次创建 Run | 仅一个 Run、所有响应映射相同 runId |
| TC-003 | AT-04 | America/New_York 有夏令时切换 | 预览跨 DST Cron | 多节点结果一致，UTC 火点可解释，缺失/重复本地时间有确定规则 |
| TC-004 | AT-05 | DAG A→B→D 与 A→C→D | A 完成且 B 慢于 C | D 必须等 B/C 都满足依赖后才开始 |
| TC-005 | AT-05 | DAG A→B→C 和 C→A | 发布 | 后端拒绝环，不产生不可变 Revision |
| TC-006 | AT-05 | R1 执行中 | 发布并运行 R2 | 旧 Run 始终按 R1 的节点/参数处理 |
| TC-007 | AT-07 | 两 Scheduler 同时扫描相同 due 窗口 | 并发 DB 领取 | unique triggerKey，一个逻辑 Run，唯一约束可复核 |
| TC-008 | AT-09 | Engine DB 提交后 RPC ACK 丢失 | Scheduler Outbox 重投 | Inbox 幂等，Run 数量不变，事件投递收敛 |
| TC-009 | AT-08 | Worker A 已领取 epoch=4 | A 断联，租约过期，B 获得 epoch=5 | B 可执行，A 的 epoch=4 完成被拒绝 |
| TC-010 | AT-10 | Handler 忽略中断 | 管理员请求取消 | UI 不提前宣布已取消；按真实事件最终收敛或异常待处理 |
| TC-011 | AT-11 | Provider 证书 A 绑定应用 A | 用应用 B 身份声称 A 调用 | 启动/握手/授权失败，不能靠 Metadata 冒充 |
| TC-012 | AT-11 | 证书已过期或 Etcd RBAC 拒绝 | 重试 RPC | Fail Closed，不降级成明文或匿名调用 |
| TC-013 | AT-13 | Worker quota=2，10 个任务 | 突发领取 | 同时运行数不超 2，未领取任务保留队列 |
| TC-014 | AT-12 | 含伪敏感参数的任务 | 执行并查询日志/Trace | Secret 不出现在响应、持久日志或指标标签中 |
| TC-015 | AT-15 | 已提交定义、存在 Outbox 和非终态租约 | 备份→恢复→服务重启 | 恢复后索引、幂等记录完整且未终态可对账 |
| TC-016 | AT-16 | DEMO_GUEST | 上传脚本/JAR 或发起任意外网访问任务 | 明确拒绝，允许的安全内置任务仍可运行 |
| TC-017 | AT-14 | 契约版本 N/N+1 | Provider/Consumer 逐组滚动升级与回退 | 兼容表列出的所有组合通过，不破坏在途 Run |
| TC-018 | AT-17 | Outbox backlog 超阈值 | 阻断 Engine 接口再恢复 | 告警出现并消退，原业务幂等键回放成功 |
| TC-019 | AT-01 | 清洁 Compose 环境 | 按实际部署文档首次启动 | 前端、API、RPC 与数据库迁移齐全，不能假健康 |
| TC-020 | AT-03 | Cron 非法 / 时区无效 | 发布任务 | 422，表单标出字段且没有激活计划 |

| TC-021 | AT-06 | Run 处于 RUNNING 并有历史日志 | 重启 Engine / Dispatcher / MySQL 恢复后恢复服务 | 历史不丢，未终态 Run 被安全对账，绝不伪造成功 |

## 工程运行要求
后续测试应分成纯单元/有限 DB 集成/Testcontainers/跨 JVM RPC/网络 mTLS/浏览器 Playwright/故障注入/固定硬件性能。每个 TC 绑定自动化测试类、CI job、运行日志、Commit SHA。这里列的是**待实现断言**，不是已经执行通过。
