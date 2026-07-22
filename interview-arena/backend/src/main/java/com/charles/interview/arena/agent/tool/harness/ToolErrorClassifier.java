package com.charles.interview.arena.agent.tool.harness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.tool.api.ToolResult;
import com.charles.interview.arena.agent.tool.model.ToolErrorType;

/**
 * 工具错误分类器(六分类)
 * <p>
 * 职责:将工具执行异常分类为六种类型,决定后续处理策略。
 * <p>
 * 分类逻辑:
 * 1. JSON 解析异常 -> INVALID_ARGUMENTS(允许模型修复重试)
 * 2. 权限/安全异常 -> DENIED(禁止重试,记审计)
 * 3. 网络/超时/5xx -> TEMPORARILY_UNAVAILABLE(代码重试)
 * 4. 业务异常(不存在/已结束) -> BUSINESS_ERROR(看业务含义)
 * 5. 结果为空/不满足 -> SEMANTIC_FAILURE(返Planner)
 * 6. 结果含注入/超大 -> UNSAFE_RESULT(沙箱化)
 */
@Component
public class ToolErrorClassifier {

    private static final Logger log = LoggerFactory.getLogger(ToolErrorClassifier.class);

    /**
     * 分类异常
     *
     * @param e      工具执行异常
     * @param result 工具执行结果(可能为 null)
     * @return 错误类型
     */
    public ToolErrorType classify(Exception e, ToolResult result) {
        if (e == null && result == null) {
            return ToolErrorType.SEMANTIC_FAILURE;
        }

        // 1. 异常分类
        if (e != null) {
            String className = e.getClass().getSimpleName();
            String message = e.getMessage() != null ? e.getMessage() : "";

            // 结构错误
            if (e instanceof com.fasterxml.jackson.core.JsonProcessingException
                    || message.contains("JSON")
                    || message.contains("parse")
                    || className.contains("IllegalArgument")
                    || className.contains("IllegalState")) {
                log.info("错误分类[结构错误]: {}", className);
                return ToolErrorType.INVALID_ARGUMENTS;
            }

            // 权限错误
            if (className.contains("AccessDenied")
                    || className.contains("Permission")
                    || className.contains("Security")
                    || message.contains("权限")
                    || message.contains("不允许")) {
                log.info("错误分类[权限安全]: {}", className);
                return ToolErrorType.DENIED;
            }

            // 瞬时基础设施错误
            if (className.contains("Timeout")
                    || className.contains("ConnectException")
                    || className.contains("SocketTimeout")
                    || message.contains("429")
                    || message.contains("503")
                    || message.contains("502")
                    || message.contains("Connection refused")
                    || message.contains("unavailable")) {
                log.info("错误分类[瞬时基础设施]: {}", className);
                return ToolErrorType.TEMPORARILY_UNAVAILABLE;
            }

            // 业务错误
            if (message.contains("不存在")
                    || message.contains("已结束")
                    || message.contains("已完成")
                    || className.contains("NotFound")) {
                log.info("错误分类[业务错误]: {}", className);
                return ToolErrorType.BUSINESS_ERROR;
            }
        }

        // 2. 结果分类(工具执行成功但结果可能有问题)
        if (result != null && result.isSuccess()) {
            Object data = result.getData();

            // 结果为空/不满足目标
            if (data == null
                    || (data instanceof java.util.Collection<?> c && c.isEmpty())
                    || (data instanceof String s && s.isBlank())) {
                log.info("错误分类[结果不满足目标]");
                return ToolErrorType.SEMANTIC_FAILURE;
            }

            // 结果不安全或过大
            String dataStr = String.valueOf(data);
            if (dataStr.length() > 50000) {
                log.info("错误分类[结果过大]: length={}", dataStr.length());
                return ToolErrorType.UNSAFE_RESULT;
            }
        }

        // 默认:瞬时错误(保守策略,允许重试)
        return ToolErrorType.TEMPORARILY_UNAVAILABLE;
    }

    /**
     * 根据错误类型决定是否允许模型重试
     */
    public boolean canRetryByModel(ToolErrorType errorType) {
        return errorType.isRetryableByModel();
    }
}
