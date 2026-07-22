package com.charles.interview.arena.agent.memory.semantic.model;

import java.util.List;

import lombok.Data;

/**
 * 用户知识画像 BO（蓝图 §5.4.5）
 * <p>
 * 用于面试开始时加载用户画像，驱动智能出题。
 */
@Data
public class UserProfile {

    /** 用户 ID */
    private Long userId;

    /** 总面试次数 */
    private Integer totalInterviews;

    /** 历史平均分 */
    private Double avgScore;

    /** 薄弱点列表（mastery < 60） */
    private List<WeakPoint> weakPoints;

    /** 偏好标签列表 */
    private List<String> preferredTags;

    /** AI 生成的推荐复习方向 */
    private String recommendedReview;
}
