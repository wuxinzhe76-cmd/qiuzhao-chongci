package com.charles.interview.arena.agent.perception.model;

import java.util.List;
import java.util.Map;

/**
 * 感知结果(感知层输出契约)
 */
public record PerceptionResult(
        List<Observation> observations,
        Intent intent,
        Map<String, Object> entities,
        RiskAssessment riskAssessment,
        TrustLevel trustLevel
) {
    public static PerceptionResult blocked(String reason) {
        return new PerceptionResult(
                List.of(), Intent.UNKNOWN, Map.of(),
                new RiskAssessment("BLOCKED", reason), TrustLevel.UNTRUSTED);
    }
}
