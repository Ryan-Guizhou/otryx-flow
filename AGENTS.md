# OTRYX Flow 工程契约（M0 Proposed）

- **单一事实源**：PRD 定义业务行为，ADR 定义技术决策，API/RPC 文档定义契约；任何变更需连同测试/验收映射一起修改。
- **依赖**：Java 21 + Spring Boot 3.5.4；OTRYX RPC 仅一个 Starter，按需显式选 Registry Adapter，先验证/固定 Git Commit；不能把 `1.0.0-SNAPSHOT` 视为已发布制品。
- **编码**：`com.peachsoft.otryx.flow` 包名；类型/方法中文 Javadoc；英文结构化日志；字面量可复用时提取常量；统一错误码与边界校验。完整规则以 [Java 工程编码规范](docs/engineering/java-coding-standard.md) 为单一事实源：Java 21、UTF-8/LF、4 空格、120 字符、显式 import、可核验类型元数据、方法契约注释和脱敏参数化日志。新增/修改 Java 文件执行 `python3 scripts/test_check_java_style.py` 与 `python3 scripts/check_java_style.py --changed --base <base-sha>`；历史债务用 `--audit` 报告，不得伪造作者/时间或把风格整理混入行为变更。
- **服务**：业务服务间同步远程调用只用 OTRYX RPC；不要引入 Feign/Dubbo 替换核心链路。Gateway/API 使用 HTTPS/REST。数据按服务 Schema 归属，无跨库直写。
- **正确性**：任务是 **at-least-once**，外部副作用必须幂等；RPC 成功不代表业务被持久处理；超时/取消不代表执行进程立即停止。
- **可靠性**：事务 Outbox、消息 Inbox 去重、可重放接收确认、Worker Lease/Fencing、边界资源配额和补偿闭环；EventLoop 不执行阻塞任务。
- **安全**：公网端只访问代理与 REST API；网络部署使用 OTRYX `NETWORK_MTLS`、受保护 Etcd、实际证书绑定和 Provider 明确授权；示例 `TRUSTED_DEVELOPMENT` 不能用于公网业务网络。
- **开发流程**：一 Issue 一 PR；变更源码/测试/文档一起交付；PR Head 验证通过再合并；合并不重复完整重型门禁，物理机长稳/性能保留专用流水线。
- **文档准入**：README 中文/英文段落相同；Mermaid 可渲染；链接有效；需求 ID → 验收 ID 映射完整。执行 `python3 scripts/check_docs.py`。
- **发布安全**：未经人工明确批准不发布生产镜像、Tag、Release、改写生产 Secret 或执行高风险远程部署。
