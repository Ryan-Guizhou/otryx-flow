package com.peachsoft.otryx.flow.api.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * 生产安全配置不能回退到开发信任模式。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 11:20
 */
class ProductionProfileGuardTest {
    @Test
    void developmentProfileStartsAndMtlsProfileFailsClosed() {
        assertDoesNotThrow(() -> ProductionProfileGuard.assertAllowed("TRUSTED_DEVELOPMENT", false));
        assertThrows(IllegalStateException.class,
                () -> ProductionProfileGuard.assertAllowed("NETWORK_MTLS", false));
        assertThrows(IllegalStateException.class,
                () -> ProductionProfileGuard.assertAllowed("TRUSTED_DEVELOPMENT", true));
    }
}
