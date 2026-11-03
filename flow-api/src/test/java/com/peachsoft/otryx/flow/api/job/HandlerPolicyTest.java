package com.peachsoft.otryx.flow.api.job;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 校验演示 Handler 白名单。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 11:20
 */
class HandlerPolicyTest {
    @Test
    void acceptsSafeEchoAndRejectsCodeOrUrlPayloads() {
        assertTrue(HandlerPolicy.allowed("safe.echo", "{}"));
        assertFalse(HandlerPolicy.allowed("shell.exec", "{}"));
        assertFalse(HandlerPolicy.allowed("safe.echo", "{\"url\":\"https://example.test\"}"));
        assertFalse(HandlerPolicy.allowed("safe.echo", "{\"cmd\":\"Runtime.getRuntime\"}"));
    }
}
