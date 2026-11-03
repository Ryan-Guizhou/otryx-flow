# Handler 目录

Metadata 的 `flow_handler_catalog` 登记可发布的 Handler。当前种子是 `safe.echo`、`safe.fail` 和 `safe.summarize`。

`safe.summarize` 读取 JSON 对象里的 `values` 数字数组，把求和结果写入 Worker 日志 `summarize.sum=<total>`。它不执行脚本或动态类加载。

立即执行还要求至少一台心跳未过期的 Worker 声明了同一个 Handler Key。没有在线 Worker 时接口返回 `HANDLER_UNAVAILABLE`。
