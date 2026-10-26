# 实施策略、PR 和质量门禁（Proposed）

## 开发顺序
1. M0 文档/ADR → 创建独立仓库与保护分支 → 固定 OTRYX RPC Commit/Starter → 实现第一个真实独立 JVM RPC Contract。
2. API 身份/项目/RBAC 与 Metadata 定义存储并行推进，提前冻结统一 OpenAPI、错误码、数据迁移规则。
3. Scheduler `due fire + outbox`、Engine `createRun + Inbox`、Dispatcher `enqueue + lease`、Worker SDK 的“单节点成功链路”先完成，再补故障恢复与竞态测试。
4. DAG Engine 和 Vue Flow 画布迭代；禁止先做好静态前端并宣称平台可用。
5. mTLS 受保护 Etcd 与应用授权、监控、备份、灰度与压力等非功能闭环。
6. 固定环境发布验收、GHCR 镜像、受限站点和真实 URL。

## 代码组织
后端多模块 Maven，契约/公共 DTO 不依赖服务实现；Vue3/TS 单前端应用；`flow-quickstart` 仅提供经过验证的最小跨进程调用与 Handler 演示。禁止添加重复 Starter、使用 Feign 与 OTRYX 混合实现同一同步 RPC 业务调用。

## 一个 Issue 对应一个 PR
每个 Issue 包含业务目标、约束、关联 `FR/NFR/AT`、依赖 PR、验收命令、回滚风险。PR 一次性涵盖该纵向切片的代码、迁移、接口、测试和相关文档；禁止为追求文件少而反复拆出微小 PR，也不要把多个不相关 Issue 合成同一个 PR。

## 变更感知 CI
始终执行轻量检查：目录、格式、敏感信息扫描、文档链接与 PR 元数据；Java/前端按路径变化执行编译与单测，数据库/schema、RPC/配置、安全变化触发对应集成矩阵。Release Candidate 独立运行跨 JVM、完整网络 mTLS、负载/长稳、备份恢复及浏览器 E2E。已通过的准确 Head 校验合并 main 后不重复无必要的重型检查。

## 例外处理
任何 P0 安全错误、调度重复权威状态、未记录的工作流数据丢失、无法恢复数据库迁移、RPC 错误身份验证均为 Blocker。无法在目标窗口解决的任务必须留下复现步骤、最后确认的 SHA、失败日志、未修改/已修改清单和恢复步骤；不假报完成。
