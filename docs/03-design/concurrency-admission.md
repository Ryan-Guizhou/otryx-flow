# 项目配额

入队在事务内锁定已有的项目配额行。`FORBID` 只统计 `target_type=JOB` 的活动任务，因此同一个 DAG 的多个节点不会被当成重复 Job。

`ALLOW` 在活动数达到 `maxInflight` 时返回 `CAPACITY_WAIT`。达到 `maxQueued` 时返回 `CAPACITY_REJECTED`。这两种结果都会让 Engine 继续重试，不会把 Run 写成最终 `FAILED`。DAG 派发遇到容量结果时也不再因为重试预算耗尽而进入死信。

领取一次最多扫描 32 条候选，避免排在前面的满额项目占住全部名额。
