package com.charles.interview.arena.agent.perception.model;

/**
 * 信任级别(标记内容来源可信度)
 */
public enum TrustLevel {
    UNTRUSTED,  // 用户输入/外部PDF/网页/API/工具返回
    TRUSTED,    // 系统内部状态
    VERIFIED    // 经过验证的 DB 事实
}
