package com.charles.interview.arena.agent.tool.api;

import org.springframework.stereotype.Component;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.charles.interview.arena.agent.guardrail.tool.ToolPermission;
import com.charles.interview.arena.agent.tool.harness.ToolResultSanitizer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 工具执行器(含 Harness 拦截 + Sentinel 限流 + 返回沙箱化)
 * <p>
 * 职责:
 * 1. 限流:Sentinel QPS 限流
 * 2. 权限检查:四级权限(READ/WRITE/EXECUTE/CRITICAL)
 * 3. 审计日志:记录调用名称、耗时、结果
 * 4. 返回沙箱化:ToolResultSanitizer 处理(大小限制/脱敏/注入扫描/标记 UNTRUSTED)
 * 5. 异常兜底:工具异常返回 ToolResult.failure
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutor {

    private final ToolRegistry toolRegistry;
    private final ToolResultSanitizer toolResultSanitizer;

    /**
     * 执行工具
     */
    public ToolResult execute(String toolName, ToolInput input) {
        // 1. Sentinel 限流
        try (Entry entry = SphU.entry(toolName)) {
            return doExecute(toolName, input);
        } catch (BlockException e) {
            log.warn("[Tool] 限流拦截: {} | rule={}", toolName, e.getRule());
            return ToolResult.failure("系统繁忙，请稍后重试");
        }
    }

    /**
     * 实际执行工具(限流通过后)
     */
    private ToolResult doExecute(String toolName, ToolInput input) {
        // 1. 查找工具
        Tool tool;
        try {
            tool = toolRegistry.get(toolName);
        } catch (IllegalArgumentException e) {
            log.warn("[Tool] 工具不存在: {} | sessionId={}", toolName, input.getSessionId());
            return ToolResult.failure("工具不存在: " + toolName);
        }

        // 2. 权限检查(四级:READ/WRITE/EXECUTE/CRITICAL)
        ToolResult permissionResult = checkPermission(tool, input);
        if (permissionResult != null) {
            return permissionResult;
        }

        // 3. 审计日志(开始)
        long startTime = System.currentTimeMillis();
        log.info("[Tool] 开始执行: {} | sessionId={}", toolName, input.getSessionId());

        // 4. 执行工具
        try {
            ToolResult result = tool.execute(input);

            // 5. 返回沙箱化(防间接注入:大小限制/脱敏/注入扫描/标记 UNTRUSTED)
            if (result.isSuccess()) {
                result = toolResultSanitizer.sanitize(result);
            }

            // 6. 审计日志(结果)
            long elapsed = System.currentTimeMillis() - startTime;
            if (result.isSuccess()) {
                log.info("[Tool] 执行成功: {} | 耗时={}ms", toolName, elapsed);
            } else {
                log.warn("[Tool] 执行失败: {} | 耗时={}ms | error={}", toolName, elapsed, result.getErrorMessage());
            }
            return result;

        } catch (Exception e) {
            // 7. 异常兜底
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("[Tool] 执行异常: {} | 耗时={}ms | error={}", toolName, elapsed, e.getMessage(), e);
            return ToolResult.failure("工具执行异常: " + e.getMessage());
        }
    }

    /**
     * 权限检查(四级)
     * <p>
     * - READ:直接放行
     * - WRITE:记录日志后放行
     * - EXECUTE:需人工审批(HITL,当前返回失败)
     * - CRITICAL:禁止自主执行
     */
    private ToolResult checkPermission(Tool tool, ToolInput input) {
        ToolPermission.Level level = tool.getPermissionLevel();

        return switch (level) {
            case READ -> null; // 放行
            case WRITE -> {
                log.info("[Tool] WRITE 权限审计: {} | sessionId={}", tool.getName(), input.getSessionId());
                yield null; // 放行
            }
            case EXECUTE -> {
                log.warn("[Tool] EXECUTE 权限拦截: {} 需人工审批(HITL)", tool.getName());
                yield ToolResult.failure("EXECUTE 操作需人工审批(HITL)");
            }
            case CRITICAL -> {
                log.warn("[Tool] CRITICAL 权限拦截: {} 禁止自主执行", tool.getName());
                yield ToolResult.failure("CRITICAL 操作禁止自主执行");
            }
        };
    }
}
