# M2 FLOW-012 验收规范 / Issue #7

**状态：PR 开发候选，合并前需执行真实门禁；禁止将当前文档本身视为成功证据。**

## DoD 与证据来源

1. `mvn -B -ntp clean verify`：Dispatcher H2 测试覆盖同 runId 入队幂等、载荷冲突、项目隔离、两副本竞争领取、租约续期、过期接管、旧代际回执 `STALE`、失败重试、最大尝试预算和请求上限。
2. Engine 单测：Inbox + Run + 新 Dispatch Outbox **同事务**；RPC 在事务外；Dispatcher 不可用重试、重复 ACK 和缺失不可变 JobRevision 的隔离状态。
3. Vue `npm run build` 与 Node API 测试，REST 禁止对外公开 leaseToken 或 Worker 完成操作。
4. `scripts/check_docs.py`, `scripts/check_m1_source.py`：迁移、Docker 复制、Compose 双副本配置、真实工作流脚本。
5. Compose：运行 MySQL、Etcd、Metadata、Scheduler×2、Engine、Dispatcher×2、API 和 Web；真实运行既有登录、Cron/Trigger/Outbox、Engine Inbox/Run 端到端测试。
6. 同次 Compose：恢复 Engine Dispatch Outbox 进行 ACK 丢失模拟，数据库 Dispatcher Task 条数不变；CI `e2e` profile 内部 Probe 实际领取、人工过期旧租约、新代际接管、拒绝旧完成、允许新心跳和结果确认；读取 SQL Attempt 审计证明历史未覆盖。
7. PR 当前 Head 的 `docs/source/frontend/backend/compose-integration` **五项全绿**，才可 squash merge `main` 并自动关闭 #7。

## 不得虚报

此阶段真实 Worker Handler 尚未执行；Probe 只是特定 CI profile 的内部测试入口。不能把 `DispatcherTask.SUCCEEDED`（Probe 提交）等同于真实 Worker SDK 自动执行成功；不得宣称 EngineRun 已汇总真实 Handler 的最终 Outcome。下一阶段 #8/#9 验证可信 Handler 执行、心跳、取消、对账与故障恢复。
