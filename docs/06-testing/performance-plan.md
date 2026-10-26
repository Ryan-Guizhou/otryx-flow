# 受控性能与容量验证计划（Proposed）

## 为什么不能先给“支持 10k 并发”的承诺
OTRYX RPC 的 10k logical-concurrency harness 验证的是 RPC 逻辑并发能力，不能替代 Flow 业务链路的任务吞吐、持久化 DB 竞争、Worker Handler 耗时与积压能力。固定物理机基线必须独立测量并保留原始证据。

## 必填测量环境
提交 SHA、OTRYX RPC Commit/Artifact Hash、CPU 型号/核心数、物理/虚拟化类型、RAM、存储、OS/JDK/GC/JVM 参数、MySQL/Etcd 版本、各服务副本数、网络拓扑、Handler 类型、Payload、workerSlots、任务生成速率及持续时间。

## 需要分开的矩阵
| 实验 | 自变量 | 测量目标 |
|---|---|---|
| RPC 基线 | 请求 Payload、客户端并发、连接 shard | p50/p99/p99.9、QPS/Core、allocation/op、GC |
| 调度火点 | Scheduler 数、Cron 密度、数据库索引 | fire lag p95/p99、唯一性、DB Lock Wait |
| Dispatch 领取 | Worker 实例数、slots、batch、poll | Claim QPS、吞吐、排队时长、DB CPU |
| DAG 执行 | 节点数、扇出、条件分支 | Run 时延、依赖推进次数、重复派发率 |
| 长稳 | 短 IO/长 IO/CPU、故障注入 | 租约过期、堆/直接内存、泄漏、长期堆积 |
| 过载 | 输入速率大于处理速率 | 拒绝/排队行为有界、错误分类正确 |

## 正式 SLO 如何冻结
在 M4 先取得无压基线，M5 前用固定机实验提出目标，记录吞吐下限、p99 上限、积压恢复时间、Worker 故障恢复时间和错误比例。由维护者在 Release Candidate 前签定数值，不得在测试后根据结果倒填目标。

## 证据程序
预热 → ≥3 次重复受控运行 → 原始 CSV/JSON/指标快照和火焰图 → 环境比对 → 统计稳定性判断 → 改动前后差异 → 人工复核；不可将 GitHub shared runner smoke 直接包装为生产容量证据。

## 安全与节约成本
压测环境和真实公网 Demo 严格隔离；压力测试不对外部第三方服务发真实通知。所有任务使用可信 Handler，失败自动停止并有资源预算。
