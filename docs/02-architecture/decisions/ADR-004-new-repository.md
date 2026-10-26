# ADR-004：创建独立 `otryx-flow`，不复用旧仓库

**状态：Proposed / 2026-10-23**

**背景**：GitHub 账户存在旧 [peach-scheduler](https://github.com/Ryan-Guizhou/peach-scheduler)，README 说明它是 Spring Boot + Quartz 的轻量调度工程，目标与全新 OTRYX RPC + DAG 前后端微服务不同。

**决策**：新建 `Ryan-Guizhou/otryx-flow`，保留旧工程作历史参考，不对旧仓库执行覆盖、重命名、许可证替换、分支清理或破坏性迁移。

**后果**：独立 Issue、CI、权限、品牌文档、Release 与部署；需要新仓库创建后进行 M0 文件推送。
