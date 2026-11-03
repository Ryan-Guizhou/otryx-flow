package com.peachsoft.otryx.flow.api.project;

import com.peachsoft.otryx.flow.contracts.v1.DispatcherRpc;
import com.peachsoft.otryx.flow.contracts.v1.JobRpc;
import com.peachsoft.otryx.flow.contracts.v1.JobRunRpc;
import com.peachsoft.otryx.flow.contracts.v1.ProjectRpc;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import java.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页使用真实列表长度，不再返回里程碑占位。
 *
 * <p>每个项目最多汇总一次有界列表。计数是当前可见快照，不是集群指标。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
@RestController
public class OverviewController {
    @OtryxRpcReference(version = "1.0.0") private ProjectRpc projects;
    @OtryxRpcReference(version = "1.0.0") private JobRpc jobs;
    @OtryxRpcReference(version = "1.0.0") private JobRunRpc runs;
    @OtryxRpcReference(version = "1.0.0") private DispatcherRpc dispatcher;

    /** 汇总当前用户可见的项目、任务、执行和在线 Worker。 */
    @GetMapping("/api/v1/overview")
    public Map<String, Integer> overview(Principal principal) {
        var owned = projects.listByOwner(principal.getName()).projects();
        int jobCount = 0;
        int runCount = 0;
        int scanned = 0;
        for (var project : owned) {
            if (scanned++ >= 20) break;
            jobCount += jobs.list(principal.getName(), project.id()).jobs().length;
            runCount += runs.list(principal.getName(), project.id()).runs().length;
        }
        int online = 0;
        for (var worker : dispatcher.listWorkers()) {
            if ("ONLINE".equals(worker.status())) online++;
        }
        return Map.of("projects", owned.length, "jobs", jobCount, "runs", runCount, "workersOnline", online);
    }
}
