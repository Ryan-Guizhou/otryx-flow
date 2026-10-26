# FLOW-013 / Issue #8：Worker SDK 验收清单

最新 PR Head 必须分别通过以下门禁，**禁止用静态或模拟测试代替真实执行**：

1. `mvn -B -ntp clean verify`：WorkerRuntime 单测覆盖合法 Handler、未注册 Handler 拒绝、资源配置校验、真实完成回执、超时协作取消、STALE 心跳抑制旧回执、Drain。
2. Vue + Node 测试：无回归；Web 端不可访问 Dispatcher `leaseToken` 或 Worker 控制 RPC。
3. 静态检查与双语文档：新增 SDK/Quickstart 模块均加入 Maven 反应器、Dockerfile、CI paths，保持 Nacos 3.2.4 单一注册中心与 Bootstrap 顺序。
4. 真实 Compose：MySQL、Nacos+8 分片 Bootstrap、Metadata、Scheduler×2、Engine、Dispatcher×2 通过此前全部集成测试；**只有在 Dispatcher 现有 Fencing 探针结束后**，才使用 Compose profile `worker-demo` 启动真实 Worker JVM。
5. 工程 E2E：同一项目发布 revision=2 的 `safe.echo` 任务生成新的 Cron 火点；SDK Consumer 通过 Nacos 发现 Dispatcher；领取、执行 Handler、完成上报后，Dispatcher 数据库中存在由 `flow-worker-demo` 产生的 `SUCCEEDED` Attempt；无 token 出现在 REST 页面。
6. 退出：Worker 进程可主动停止；上游已有损坏事件、重投和重启用例保持绿色，Engine 不会被擅自标记 `SUCCEEDED`（Issue #9 才实现）。

开发执行入口 `scripts/test_m2_worker_e2e.sh` 需要前置运行 `test_m2_job_e2e.sh` 到 Dispatcher 真实集成环节；它依赖会话 Cookie、CSRF Token、项目和已发布的 Job UUID。不要在生产环境执行该脚本或启用 `FLOW_DISPATCH_E2E_PROFILE=e2e`。
