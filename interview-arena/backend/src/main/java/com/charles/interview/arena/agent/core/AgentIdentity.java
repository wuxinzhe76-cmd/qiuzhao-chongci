package com.charles.interview.arena.agent.core;

/**
 * Agent 身份信息(注入工具调用,防伪造)
 */
public record AgentIdentity(
        Long userId,
        Long sessionId
) {}
