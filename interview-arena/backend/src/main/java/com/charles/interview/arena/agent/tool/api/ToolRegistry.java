package com.charles.interview.arena.agent.tool.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 工具注册中心
 * <p>
 * 设计参考：LangChain ToolRegistry + Spring AI FunctionCallbackContext
 * <p>
 * 职责：
 * 1. 启动时注册所有 Tool Bean
 * 2. 运行时按名称查找工具
 * 3. 支持列出所有已注册工具（用于调试和日志）
 * <p>
 * 工具注册方式：Spring 自动扫描所有实现了 Tool 接口的 @Component/@Service Bean，
 * 启动时通过构造函数注入 List<Tool> 自动注册。
 */
@Slf4j
@Component
public class ToolRegistry {

    /** 工具名称 -> 工具实例 */
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * Spring 自动注入所有 Tool Bean
     */
    public ToolRegistry(List<Tool> toolList) {
        for (Tool tool : toolList) {
            register(tool);
        }
        log.info("工具注册中心初始化完成，已注册 {} 个工具: {}", tools.size(), tools.keySet());
    }

    /**
     * 注册工具
     */
    public void register(Tool tool) {
        String name = tool.getName();
        if (tools.containsKey(name)) {
            log.warn("工具名称冲突，覆盖已注册的工具: {}", name);
        }
        tools.put(name, tool);
        log.info("注册工具: {} ({}) [权限: {}]", name, tool.getDescription(), tool.getPermissionLevel());
    }

    /**
     * 按名称查找工具
     */
    public Tool get(String name) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未找到工具: " + name + "，已注册: " + tools.keySet());
        }
        return tool;
    }

    /**
     * 列出所有已注册工具
     */
    public List<Tool> list() {
        return new ArrayList<>(tools.values());
    }

    /**
     * 检查工具是否已注册
     */
    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /**
     * 渲染工具清单（注入 ReAct 系统提示词，供 LLM 选择工具）
     *
     * @param toolNames 本 Agent 允许使用的工具名（白名单，不同 Agent 可见的工具集不同）
     * @return 形如 "- pickQuestion: 从题库抽题... 参数: {...}" 的多行文本
     */
    public String renderToolPrompt(List<String> toolNames) {
        StringBuilder sb = new StringBuilder();
        for (String name : toolNames) {
            Tool tool = tools.get(name);
            if (tool == null) {
                log.warn("渲染工具清单：工具未注册，跳过: {}", name);
                continue;
            }
            sb.append("- ").append(tool.getName())
              .append(": ").append(tool.getDescription())
              .append(" | 参数: ").append(tool.getInputSchema())
              .append("\n");
        }
        return sb.toString();
    }
}
