package com.peachsoft.otryx.flow.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 在进程启动时拒绝把开发信任配置当成生产网络安全配置。
 *
 * <p>当前固定的 Nacos Adapter 不提供 NETWORK_MTLS。要求 mTLS 或显式选择该配置时直接失败，
 * 不回退到 TRUSTED_DEVELOPMENT。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 11:20
 */
@Component
public class ProductionProfileGuard implements ApplicationRunner {
    private final String profile;
    private final boolean requireMtls;

    public ProductionProfileGuard(
            @Value("${otryx.rpc.security-profile}") String profile,
            @Value("${flow.security.require-mtls:false}") boolean requireMtls) {
        this.profile = profile;
        this.requireMtls = requireMtls;
    }

    @Override
    public void run(ApplicationArguments args) {
        assertAllowed(profile, requireMtls);
    }

    /**
     * @param profile OTRYX RPC 安全配置名
     * @param requireMtls 为 true 时开发信任配置也不能启动
     */
    static void assertAllowed(String profile, boolean requireMtls) {
        if (profile == null || profile.isBlank()) {
            throw new IllegalStateException("OTRYX RPC security profile is required");
        }
        if ("NETWORK_MTLS".equals(profile) || requireMtls) {
            throw new IllegalStateException(
                    "NETWORK_MTLS is not available in the pinned Nacos adapter; "
                            + "OTRYX RPC issue 179 still blocks production identity");
        }
        if (!"TRUSTED_DEVELOPMENT".equals(profile)) {
            throw new IllegalStateException("Unsupported OTRYX RPC security profile");
        }
    }
}
