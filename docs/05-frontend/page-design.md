# 页面级设计规格（Proposed）

| 页面 / 路由草案 | 主要区域 | 空/错状态 | 权限 |
|---|---|---|---|
| `/login` | 登录表单、错误提示 | 速率限制提示，不泄露用户存在性 | 访客 |
| `/overview` | 活跃 Run、成功率、积压、Worker 容量 | 指标无数据与监控故障区分 | VIEWER+ |
| `/projects/:p/jobs` | 任务列表、搜索、版本、手动执行 | 空项目引导创建；禁用任务解释 | VIEWER/OPERATOR/OWNER |
| `/projects/:p/schedules` | Cron、时区、下一次 Fire、策略 | Cron 非法高亮并禁用发布 | VIEWER/OWNER |
| `/projects/:p/workflows` | 工作流版本/草稿列表 | 无已发布版明确提示 | VIEWER/OWNER |
| `/projects/:p/workflows/:w/edit` | DAG 画布、属性面板、校验区、版本发布 | 冲突、环、失联、无 Handler 拒绝发布 | OWNER |
| `/projects/:p/runs` | 筛选、运行状态、耗时、触发来源 | 支持分页，隐藏他人项目 | VIEWER+ |
| `/projects/:p/runs/:r` | DAG 状态回放、尝试、日志、错误归因 | 不确定状态显示原因/人工恢复建议 | VIEWER+ |
| `/projects/:p/workers` | Worker 组、容量、最近心跳、标签 | 未注册或心跳过期有明确提示 | VIEWER+ |
| `/projects/:p/audit` | 变更事件、操作者和时间 | 不展示 Secret，拒绝无权访问 | OWNER |
| `/projects/:p/settings` | 成员、配额、保留期、告警 | 修改前风险确认/版本冲突 | OWNER |

### Dashboard 数据源与口径
“成功率”需明确采样窗口和分母（已完成/已失败 Run）；“排队量”来自 Dispatcher 权威状态；“节点离线”来自 WorkerPresence 的 Heartbeat 失效。前端不能将 RPC 注册成员数直接等同可执行 Worker 槽位数。

### 操作流程示例
登录 → 选择项目 → 新建任务并预览 Cron → 创建 DAG 草稿 → 后端校验 → 发布 R1 → 触发执行 → 跳转 Run 详情 → 选择 Node 查看 Attempts/日志 → 权限足够时发起 Retry/Cancel 并查看审计。
