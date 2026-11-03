package com.peachsoft.otryx.flow.api.job;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 可信 Handler 标识与参数的拒绝规则。
 *
 * <p>只允许 safe. 前缀。参数里的 URL、路径穿越和进程执行字样一律拒绝，避免演示入口变成任意代码。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 11:20
 */
public final class HandlerPolicy {
    private static final Pattern SAFE_KEY = Pattern.compile("safe\\.[a-z][a-z0-9_.-]{0,120}");

    private HandlerPolicy() { }

    /**
     * @param handlerKey 任务绑定的 Handler 标识
     * @param parametersJson 对象形态的 JSON 文本
     * @return 标识和参数都落在白名单内时为 true
     */
    public static boolean allowed(String handlerKey, String parametersJson) {
        if (handlerKey == null || !SAFE_KEY.matcher(handlerKey).matches()) return false;
        if (parametersJson == null) return false;
        String folded = parametersJson.toLowerCase(Locale.ROOT);
        return !folded.contains("://")
                && !parametersJson.contains("../")
                && !folded.contains("runtime")
                && !folded.contains("processbuilder")
                && !folded.contains("class.forname");
    }
}
