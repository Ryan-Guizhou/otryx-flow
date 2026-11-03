package com.peachsoft.otryx.flow.api.audit;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理操作审计查询。
 *
 * <p>只返回操作者、动作、项目、结果和时间。不返回参数正文。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 11:20
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {
    private final FlowAuditRecorder recorder;

    public AuditController(FlowAuditRecorder recorder) {
        this.recorder = recorder;
    }

    /** 最近 100 条审计。DEMO_GUEST 不能调用。 */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<FlowAuditRecorder.AuditEvent> latest() {
        return recorder.latest();
    }
}
