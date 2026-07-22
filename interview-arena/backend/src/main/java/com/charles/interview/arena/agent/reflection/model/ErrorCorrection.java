package com.charles.interview.arena.agent.reflection.model;

/**
 * 纠错类型(四类)
 * <p>
 * 反思时检查什么 + 不满足时的纠正方式。
 */
public enum ErrorCorrection {

    /** 参数错误:工具返回参数不合法 -> 修正参数,重试同一工具 */
    PARAM_ERROR("参数错误", "修正参数,重试同一工具"),

    /** 工具选错:工具返回空或不相关 -> 换一个工具 */
    TOOL_SELECTION_ERROR("工具选错", "换一个工具"),

    /** 信息不足:结果不够全面 -> 补充检索(多调一个工具) */
    INFO_INSUFFICIENT("信息不足", "补充检索"),

    /** 逻辑错误:推理过程有误 -> 重新推理,可能重规划 */
    LOGIC_ERROR("逻辑错误", "重新推理");

    private final String displayName;
    private final String correctionStrategy;

    ErrorCorrection(String displayName, String correctionStrategy) {
        this.displayName = displayName;
        this.correctionStrategy = correctionStrategy;
    }

    public String getDisplayName() { return displayName; }
    public String getCorrectionStrategy() { return correctionStrategy; }
}
