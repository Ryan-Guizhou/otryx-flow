# M2 FLOW-010 Scheduler 验收计划

## 需求对应

- FR-007 / FR-008 / AT-07：已发布任务生成持久化 Cron fire，两个 Scheduler 副本不会重复建立逻辑火点。
- AT-09：任意单实例重启后游标继续推进，Outbox 与 Trigger 在同一事务持久化，用户修改启停使用 CAS。

## 测试及证据

| Case | 操作 | 预期 |
| --- | --- | --- |
| SCH-01 | 使用发布后 JobRevision 创建 Cron | HTTP 201，绑定不可变版本与 UTC 游标 |
| SCH-02 | 重放相同 Idempotency-Key | HTTP 200，Schedule ID 相同 |
| SCH-03 | 相同 Key + 不同请求 | HTTP 409，不覆盖原有计划 |
| SCH-04 | 两个 Scanner 并发扫描同一到期时间 | 逻辑 Trigger 唯一索引无重复，对应 Outbox 数相同 |
| SCH-05 | 重启其中一个 Scheduler 进程 | 另一个副本继续生成记录，重启后进程恢复 |
| SCH-06 | 暂停 + 旧版本再次请求 | 新版本成功，旧版本返回 409 |
| SCH-07 | Metadata 无对应 published revision | 不创建调度，返回 404 |
| SCH-08 | 未登录访问/无效 CSRF | 401/403，不能写入 Scheduler |
| SCH-09 | `SKIP/FIRE_ONCE/CATCH_UP` 的旧触发 | 不无限补触发，符合有限窗口约束 |
| SCH-10 | 在 Engine 尚未上线前查询火点 | `PENDING_ENGINE`，Outbox 保持 `PENDING`，**不能声称执行成功** |

### 真实验证入口

- Java: `mvn -B -ntp clean verify`，覆盖 Scheduler 和现有服务的全部测试。
- Docker Compose: `scripts/test_m1_e2e.sh` → `scripts/test_m2_job_e2e.sh` → `scripts/test_m2_scheduler_e2e.sh`。必须使用两个 Scheduler 容器，读 `flow_scheduler` Schema 检查 Outbox 和重复键，并故障重启其中一个实例。
- 精确证据：GitHub PR Head SHA、检查名称、Workflow Run URL、退出码和故障恢复记录。任何运行失败都必须修复并重新运行对应检查。
