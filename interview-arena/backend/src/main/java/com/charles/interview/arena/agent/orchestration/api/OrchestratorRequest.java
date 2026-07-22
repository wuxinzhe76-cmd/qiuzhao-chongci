package com.charles.interview.arena.agent.orchestration.api;

import com.charles.interview.arena.agent.core.AgentIdentity;

/**
 * Orchestrator 请求
 */
public record OrchestratorRequest(
        AgentIdentity identity,
        String input,
        String action
) {}
