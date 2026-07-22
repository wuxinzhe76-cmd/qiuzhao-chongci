package com.charles.interview.arena.agent.guardrail.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具权限分级（Tool Permission）
 * <p>
 * 关联 Harness 层：L3 安全护栏层
 * 关联八股题号：Day04 #232 工具、#12 安全、Day05 工具权限管理
 * <p>
 * 核心职责：
 * 定义 Agent 可调用工具的权限级别，用于运行时权限校验。
 * 只有被授权的工具才能在对应权限级别下执行，防止 Agent 越权操作。
 * <p>
 * 四级权限模型：
 * <pre>
 * ┌──────────┬──────────────────────────┬─────────────────────────┐
 * │ 权限级别  │ 允许的操作                │ 示例工具                 │
 * ├──────────┼──────────────────────────┼─────────────────────────┤
 * │ READ     │ 只读：查询、搜索、读取    │ searchQuestion, getScore │
 * │ WRITE    │ 写操作：保存记录、更新    │ saveNote, updateProfile  │
 * │ EXECUTE  │ 执行命令：运行代码、shell │ runScript, deployCode   │
 * │ CRITICAL │ 危险操作：删除、修改配置  │ deleteUser, resetSystem  │
 * └──────────┴──────────────────────────┴─────────────────────────┘
 * </pre>
 *
 * <pre>
 * 使用示例：
 *   &#64;ToolPermission(level = ToolPermission.Level.READ)
 *   public String searchQuestion(String keyword) { ... }
 *
 *   &#64;ToolPermission(level = ToolPermission.Level.CRITICAL)
 *   public void deleteUser(Long userId) { ... }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolPermission {

    /**
     * 工具权限级别枚举
     * <p>
     * 四级权限：
     * - READ：只读（查询、搜索），Agent 可自由调用
     * - WRITE：写操作（保存、更新），需记录操作日志
     * - EXECUTE：执行命令（运行代码、shell），需人工审批（HITL）
     * - CRITICAL：危险操作（删除、修改配置），禁止自主执行
     */
    enum Level {
        /**
         * 只读权限：查询、搜索、读取数据
         * 安全级别最低，Agent 可自由调用。
         */
        READ,

        /**
         * 写操作权限：保存记录、更新数据
         * 安全级别中等，需要记录操作日志。
         */
        WRITE,

        /**
         * 执行命令权限：运行代码、执行 shell
         * 安全级别较高，需要人工审批（HITL）。
         */
        EXECUTE,

        /**
         * 危险操作权限：删除、修改系统配置
         * 安全级别最高，禁止自主执行。
         */
        CRITICAL
    }

    /**
     * 工具权限级别
     * <p>
     * 声明该工具方法所需的权限级别，Harness 层在工具调用前检查此注解。
     *
     * @return 权限级别枚举值，默认 READ
     */
    Level level() default Level.READ;
}
