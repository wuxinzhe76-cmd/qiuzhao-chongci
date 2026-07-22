package com.charles.interview.arena.agent.tool.model;

/**
 * 工具错误类型(六分类)
 * <p>
 * 替代原三分类(TRANSIENT/SEMANTIC/STRUCTURAL),覆盖真实工具错误场景。
 * 每类有不同处理策略。
 */
public enum ToolErrorType {

    /** 1. Tool Call 结构错误:JSON无法解析/缺字段/类型错误/调用了不存在的工具 */
    INVALID_ARGUMENTS("结构错误", true, "返回校验错误给模型,允许修复重试"),

    /** 2. 权限或安全错误:无权限/不在白名单/需人工审批/疑似注入驱动 */
    DENIED("权限安全错误", false, "禁止执行,不允许重试,记安全审计"),

    /** 3. 瞬时基础设施错误:网络超时/429/5xx/DB连接失败 */
    TEMPORARILY_UNAVAILABLE("瞬时基础设施错误", true, "代码重试->退避->Fallback->熔断"),

    /** 4. 业务错误:题目不存在/面试已结束/无符合条件数据 */
    BUSINESS_ERROR("业务错误", false, "看业务含义:换题/终止/返回受控错误"),

    /** 5. 工具成功但结果不满足目标:{questions:[]} 语义失败 */
    SEMANTIC_FAILURE("结果不满足目标", false, "返回Planner决定降难度/换知识点"),

    /** 6. 工具结果不安全或过大:含注入指令/几十万字日志 */
    UNSAFE_RESULT("结果不安全或过大", false, "沙箱化:大小限制+脱敏+注入扫描+截断");

    private final String displayName;
    private final boolean retryableByModel;
    private final String strategy;

    ToolErrorType(String displayName, boolean retryableByModel, String strategy) {
        this.displayName = displayName;
        this.retryableByModel = retryableByModel;
        this.strategy = strategy;
    }

    public String getDisplayName() { return displayName; }
    public boolean isRetryableByModel() { return retryableByModel; }
    public String getStrategy() { return strategy; }
}
