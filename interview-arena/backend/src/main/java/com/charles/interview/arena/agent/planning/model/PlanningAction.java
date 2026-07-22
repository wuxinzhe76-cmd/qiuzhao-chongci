package com.charles.interview.arena.agent.planning.model;

/**
 * 面试规划动作枚举
 * <p>
 * 定义面试过程中所有可能的动作。
 * LLM 在 Prompt 软约束下自主选择,代码硬约束验证合法性。
 */
public enum PlanningAction {

    START_INTERVIEW("开始面试", "创建面试会话,抽取第一道题,生成开场提问"),

    PROPOSE_NEXT_QUESTION("提出下一题", "切换到新题目,排除已用题目,生成过渡话术"),

    DEEP_DIVE("继续追问", "在当前题目上继续深入,追问底层原理或相关知识点"),

    LOWER_DIFFICULTY("降低难度", "当前题目太难,降低难度或换更简单的题目"),

    RAISE_DIFFICULTY("提高难度", "候选人回答优秀,提高难度或追问更深层原理"),

    SWITCH_TOPIC("切换知识点", "当前知识点已充分考察,切换到新的知识领域"),

    END_INTERVIEW("结束面试", "面试达到结束条件,生成评估报告");

    private final String displayName;
    private final String description;

    PlanningAction(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 从 LLM 返回的字符串解析动作
     * 兼容旧格式(DEEP_DIVE/NEXT_QUESTION/END_INTERVIEW)
     */
    public static PlanningAction fromString(String value) {
        if (value == null || value.isBlank()) {
            return DEEP_DIVE; // 默认继续追问
        }
        String upper = value.trim().toUpperCase();
        // 兼容旧格式
        return switch (upper) {
            case "DEEP_DIVE" -> DEEP_DIVE;
            case "NEXT_QUESTION" -> PROPOSE_NEXT_QUESTION;
            case "END_INTERVIEW" -> END_INTERVIEW;
            case "START_INTERVIEW" -> START_INTERVIEW;
            case "LOWER_DIFFICULTY" -> LOWER_DIFFICULTY;
            case "RAISE_DIFFICULTY" -> RAISE_DIFFICULTY;
            case "SWITCH_TOPIC" -> SWITCH_TOPIC;
            default -> DEEP_DIVE; // 未知动作默认继续追问
        };
    }
}
