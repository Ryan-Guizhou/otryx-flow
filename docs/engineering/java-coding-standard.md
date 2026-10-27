# OTRYX Flow Java 工程编码规范

**状态：** 首发前工程规范。本文借鉴 [OTRYX RPC Java 工程编码规范](https://github.com/Ryan-Guizhou/otryx-rpc/blob/main/docs/engineering/java-coding-standard.md)，并按 Flow 的 REST API、Scheduler、DAG Engine、Dispatcher、Worker 与数据库事务边界作了适配。

## 1. 原则与边界

优先级为：**正确性 > 安全 > 性能与可靠性 > 可理解性 > 可维护性 > 格式**。

- 纯格式、Javadoc、日志文案整理不得改变 RPC/REST 契约、数据库事务、状态机、重试、租约 fencing、线程/取消语义或资源生命周期。
- 发现行为问题时建立独立 Issue/PR，增加对应测试，不把行为修复藏在风格 PR 中。
- 不机械重构大类，不为目录对称创建无意义抽象，不抽取每一个字符串或数字，也不建立万能全局 Constants 类。
- Flow 采用 OTRYX RPC 作为服务间同步调用框架；不得以风格治理为由引入另一套 RPC 框架。
- 自动扫描只证明规则覆盖范围内没有发现问题；注释语义、日志是否泄露敏感信息、并发/事务契约仍需人工审查和测试。

## 2. 格式、命名与依赖

- Java 21；UTF-8 无 BOM、LF、4 空格缩进、120 字符行宽；禁止通配符 import、Tab 缩进和行尾空白。
- Package 使用小写英文并按领域/职责分层；类型使用 UpperCamelCase，方法/参数/字段使用 lowerCamelCase，常量使用 UPPER_SNAKE_CASE。
- 时间、超时、容量和字节数变量应在名称中注明单位，例如 `timeoutMillis`、`maxInflightBytes`、`maxLogBatchSize`。
- 常量放在最小且语义明确的所有者中；HTTP/RPC 字段、配置键、状态值、指标名等契约字面量应有稳定定义。
- 构造器注入优先；每个 Executor、队列、缓存、批次、重试和租约必须具有明确的容量、拒绝、超时、取消和关闭行为。
- Flow 的数据库 Schema 按服务归属。不要在注释或日志里暗示跨 Schema 直接写入或跨服务共享事务。

仓库根目录的 `.editorconfig` 和 `.gitattributes` 是格式默认值；IDE 自动格式化不能替代编译、测试或代码审查。

## 3. 中文 Javadoc 与注释

### 3.1 类型级文档

所有新增手写的顶层 Class、Interface、Enum、Annotation、Record 都应有中文类型 Javadoc，并记录可核验的元数据：

~~~java
/**
 * 管理一个工作流节点的持久化状态推进。
 *
 * <p>该组件只修改 Engine 自有 Schema；跨服务交付通过 Outbox 完成。</p>
 *
 * @Author <真实作者或维护者>
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/27 10:30
 */
~~~

示例中的作者与时间仅用于说明格式，不能直接复制为其他类的事实。作者、版本、创建时间必须有提交记录或维护依据；不得伪造历史元数据。对旧类型无法确认的信息，先列入审计台账，不批量编造。

### 3.2 方法契约

公共 REST/RPC 契约、事务入口、状态机、调度循环、租约续期、Worker 执行/取消、资源关闭和复杂错误路径应提供有信息量的 Javadoc。按实际情况说明：

- `@param`：取值范围、单位、可空性、所有权和校验规则。
- `@return`：状态含义、空结果、快照/可变性以及确认语义。
- `@throws`：具体失败条件、是否已提交副作用、调用方如何处理。
- 线程/阻塞/取消：是否执行阻塞 I/O、调用是否有界、取消是否仅为协作式。
- 事务/幂等/重试：事务归属、幂等键、重复调用语义、超时后结果是否未知。
- 资源生命周期：谁创建、谁关闭、拒绝/异常路径是否释放 Permit、Lease 或队列容量。

避免“获取数据”“处理请求”之类重复方法名的注释。不要逐行解释 if/for，也不要声称线程安全、幂等或 Exactly Once，除非实现和测试能证明。

### 3.3 行内注释

解释设计原因、边界约束、容易误读的竞态和非显然的安全措施。代码本身已清楚表达的赋值、循环或 getter 不需要逐行注释。注释必须随代码一起更新，不能保留过时的旧实现描述。

## 4. 英文结构化日志

保留 SLF4J 原生 `LoggerFactory`，统一英文、参数化日志。不要为风格统一而引入 Lombok 或在正常热路径打印逐请求 INFO。

~~~java
private static final Logger LOGGER = LoggerFactory.getLogger(Example.class);

LOGGER.warn("Worker heartbeat rejected, workerId={}, taskId={}, leaseEpoch={}, reason={}",
        workerId, taskId, leaseEpoch, reason);
~~~

| 级别 | 适用场景 |
| --- | --- |
| ERROR | 服务无法继续提供关键能力、不可恢复的持久化失败或需要立即介入的故障 |
| WARN | 可恢复异常、过期租约、拒绝、重试耗尽、遥测降级或未知确认结果 |
| INFO | 服务启动/关闭、关键生命周期切换、明确的管理操作结果 |
| DEBUG | 按需开启的详细诊断；必须保持脱敏并控制频率 |
| TRACE | 专项调试；评估数据敏感性与性能成本后有界使用 |

### 日志字段

按场景使用稳定且低基数的字段，例如 `requestId`、`workflowRunId`、`nodeRunId`、`taskId`、`leaseEpoch`、`errorType`、`reason`、`elapsedMillis`。这些 ID 可以出现在经过授权的日志中，但不能被直接作为 Metrics 标签。

- 使用 SLF4J 参数占位符，不预先字符串拼接或用 `String.format`。
- 不输出密码、Token、Cookie、Authorization、私钥、签名 URL、原始 RPC Metadata、业务参数正文或完整请求/响应。
- 对用户业务异常与第三方异常优先记录错误类型、分类和关联 ID；异常 message/stack trace 可能含敏感输入，不应无条件输出。
- 同一故障只在最有意义的边界记录一次，避免层层重复堆栈和高频日志风暴。
- 正常 Worker 心跳、每次 RPC 调用和每条节点推进不应默认逐条打印 INFO。
- 遥测导出失败应记录可诊断的错误类型，但不得让日志或 Metrics 出口阻塞 Handler、心跳、完成 ACK 或改变业务终态。

## 5. Flow 特有的可读性要求

- 调度器：注释应明确 Cron 时区、错过触发策略、火点唯一性和 Outbox 交付语义。
- DAG Engine：说明 Revision 固定、NodeRun logical attempt、状态推进与取消/重试竞争条件。
- Dispatcher：说明 Lease、leaseEpoch、leaseToken、过期接管、终态 Outbox 和重复 ACK 语义。
- Worker：说明 Handler Allowlist、并发上限、心跳、协作式取消、超时和至少一次执行的副作用风险。
- API/权限：说明租户/项目所有权校验、会话/CSRF 边界、错误码与审计行为；日志不能绕过授权向调用方泄露内部细节。
- 数据库：说明事务是否只操作本服务 Schema、唯一键和幂等约束；不能用日志成功替代数据库提交成功。

## 6. 自动审计与分阶段治理

运行以下命令：

~~~bash
python3 scripts/test_check_java_style.py
python3 scripts/check_java_style.py --audit --report target/java-style-audit.json
python3 scripts/check_java_style.py --changed --base <base-commit-sha>
~~~

- `--audit` 扫描所有 `src/main/java` 和 `src/test/java` 下的 Java 源码，输出格式违规、通配符导入和类型 Javadoc 元数据缺口；该模式保留历史债务，不以全仓存量缺口阻塞所有开发。
- `--changed` 只对当前 PR 变更的 Java 文件执行严格格式、导入和类型元数据检查；新增/修改类型必须满足本规范。
- 自动规则不判断作者/时间是否真实，也不能证明公共方法的注释完整、日志字段安全或业务语义正确；审计结果必须与人工 Review 一起使用。
- 新规范生效后，新建/修改的 Java 类型必须符合类型级 Javadoc 元数据规则；历史文件按审计台账逐批整改，禁止机械编造元数据清零。
- 本规范与 Issue [FLOW-031 / #32](https://github.com/Ryan-Guizhou/otryx-flow/issues/32) 对应。行为变化另开 Issue/PR。

## 7. 提交前检查

- [ ] 新增/修改类型具有准确中文 Javadoc 与可核验元数据。
- [ ] 公共/复杂方法解释参数、结果、异常、事务、线程、取消和资源边界。
- [ ] 日志为英文参数化格式，级别合适且没有敏感正文。
- [ ] 没有无意义常量抽取、万能工具类或机械重构。
- [ ] 格式/源码/文档检查通过，必要的行为测试已执行。
- [ ] README 中文/英文对同一用户可见事实保持一致。
