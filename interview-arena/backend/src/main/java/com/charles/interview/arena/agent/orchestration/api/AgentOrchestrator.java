package com.charles.interview.arena.agent.orchestration.api;

import com.charles.interview.arena.agent.core.AgentResult;

/**
 * 统一 Orchestrator 接口(两个 Agent 对齐编排模式)
 */
public interface AgentOrchestrator {
    AgentResult orchestrate(OrchestratorRequest request);
}
