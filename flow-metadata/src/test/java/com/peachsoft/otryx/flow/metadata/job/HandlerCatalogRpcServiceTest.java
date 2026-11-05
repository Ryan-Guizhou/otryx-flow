package com.peachsoft.otryx.flow.metadata.job;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.flow.contracts.v1.HandlerDefinitionView;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 验证仅隔离 E2E 配置可以公开可中断的延迟 Handler。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/5 14:00
 */
class HandlerCatalogRpcServiceTest {
    @Test
    void delayHandlerIsHiddenByDefaultAndVisibleOnlyInE2eMode() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:handler-catalog;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE flow_handler_catalog (handler_key VARCHAR(128), display_name VARCHAR(128), "
                + "parameter_schema TEXT, supports_cancel BOOLEAN, enabled BOOLEAN)");
        jdbc.update("INSERT INTO flow_handler_catalog VALUES ('safe.echo','Echo','{}',TRUE,TRUE)");

        HandlerCatalogRpcService catalog = new HandlerCatalogRpcService(jdbc);
        assertFalse(containsDelay(catalog.list()));

        ReflectionTestUtils.setField(catalog, "e2eDelayHandlerEnabled", true);
        assertTrue(containsDelay(catalog.list()));
    }

    private static boolean containsDelay(HandlerDefinitionView[] handlers) {
        for (HandlerDefinitionView handler : handlers) {
            if ("safe.delay".equals(handler.handlerKey())) return true;
        }
        return false;
    }
}
