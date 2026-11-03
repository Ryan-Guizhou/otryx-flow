package com.peachsoft.otryx.flow.metadata.job;

import com.peachsoft.otryx.flow.contracts.v1.HandlerCatalogRpc;
import com.peachsoft.otryx.flow.contracts.v1.HandlerDefinitionView;
import com.peachsoft.otryx.spring.annotation.OtryxRpcService;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 读取 Metadata 中的可信 Handler 目录。
 *
 * <p>这里不根据 Worker 心跳推断在线数量。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
@OtryxRpcService(interfaceClass = HandlerCatalogRpc.class, version = "1.0.0")
public class HandlerCatalogRpcService implements HandlerCatalogRpc {
    private final JdbcTemplate jdbc;

    /** 只查询 Metadata 自己的目录表。 */
    public HandlerCatalogRpcService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public HandlerDefinitionView[] list() {
        List<HandlerDefinitionView> rows = jdbc.query("SELECT handler_key, display_name, parameter_schema, "
                        + "supports_cancel, enabled FROM flow_handler_catalog ORDER BY handler_key LIMIT 100",
                (rs, index) -> new HandlerDefinitionView(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getBoolean(4), rs.getBoolean(5)));
        return rows.toArray(HandlerDefinitionView[]::new);
    }
}
