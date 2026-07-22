package com.charles.interview.arena.model.enums;

import lombok.Getter;

/**
 * AI 面试行为指令枚举（蓝图 §5.4）
 * <p>
 * AI 每轮返回结构化 JSON 中的 action_directive 字段取值：
 * - DEEP_DIVE: 继续追问当前知识点
 * - NEXT_QUESTION: 切换下一道题
 * - END_INTERVIEW: 结束面试
 * <p>
 * 三层控制：AI 主导 + 代码兜底（单题 >3 轮强制 NEXT_QUESTION / 总轮 >=10 强制 END_INTERVIEW）+ 用户主动结束
 */
@Getter
public enum ActionDirectiveEnum {

    DEEP_DIVE("DEEP_DIVE", "继续追问当前知识点"),
    NEXT_QUESTION("NEXT_QUESTION", "切换下一道题"),
    END_INTERVIEW("END_INTERVIEW", "结束面试");

    private final String value;
    private final String message;

    ActionDirectiveEnum(String value, String message) {
        this.value = value;
        this.message = message;
    }

    public static ActionDirectiveEnum fromValue(String value) {
        if (value == null) {
            return DEEP_DIVE;
        }
        for (ActionDirectiveEnum d : values()) {
            if (d.value.equalsIgnoreCase(value.trim())) {
                return d;
            }
        }
        return DEEP_DIVE;
    }
}
