package com.peachsoft.otryx.flow.contracts.v1;

import com.peachsoft.otryx.api.OtryxRpcContract;
import com.peachsoft.otryx.api.OtryxRpcIdempotent;

/**
 * 受信任 Handler 目录。Worker 实际上报的能力不在本契约内伪造。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
@OtryxRpcContract
public interface HandlerCatalogRpc {
    /** 返回已登记的 Handler。空目录表示尚未迁移种子数据。 */
    @OtryxRpcIdempotent
    HandlerDefinitionView[] list();
}
