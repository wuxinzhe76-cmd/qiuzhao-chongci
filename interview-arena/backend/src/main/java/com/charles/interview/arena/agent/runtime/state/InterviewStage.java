package com.charles.interview.arena.agent.runtime.state;

/**
 * 面试阶段枚举(状态机)
 */
public enum InterviewStage {
    CREATED,        // 已创建,未开始
    QUESTIONING,    // 提问中
    EVALUATING,     // 评估回答中
    ENDING,         // 正在结束
    ENDED           // 已结束
}
