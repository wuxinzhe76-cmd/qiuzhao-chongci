package com.charles.interview.arena.agent.memory.semantic.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 薄弱点 DTO（蓝图 §5.4.5）
 * <p>
 * 表示用户在某知识点上的薄弱程度，用于记忆驱动出题。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WeakPoint {

    /** 知识点名称（如 Java volatile） */
    private String topic;

    /** 历史平均掌握度 0-100 */
    private Integer avgMastery;

    /** 被考察次数 */
    private Integer examCount;

    /** 掌握度 < 60 的次数 */
    private Integer weakCount;

    /** 是否顽固薄弱点（连续 2 次 < 60） */
    private Boolean isPersistent;

    /** 最近一次考察时间戳（毫秒） */
    private Long lastExamTimestamp;
}
