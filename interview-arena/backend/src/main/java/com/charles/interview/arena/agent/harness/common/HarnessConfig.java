package com.charles.interview.arena.agent.harness.common;

import com.charles.interview.arena.agent.harness.common.CircuitBreaker;
import com.charles.interview.arena.agent.harness.common.FallbackChain;
import com.charles.interview.arena.agent.planning.harness.LoopDetector;
import com.charles.interview.arena.agent.planning.harness.StructuredErrorHandler;
import com.charles.interview.arena.agent.harness.common.TokenBudget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Harness 配置类
 * <p>
 * 关联 Harness 层：全局配置层（贯穿 L2~L5）
 * 关联八股题号：Day04 #261 编排、Day05 Harness 工程化
 * <p>
 * 核心职责：
 * 将 Harness 层的核心组件注册为 Spring Bean，供整个应用通过依赖注入使用。
 * <p>
 * 注册的 Bean：
 * <pre>
 * ┌──────────────────────┬────────────────────────────────────┬─────────┐
 * │ Bean 名称            │ 说明                                │ Harness  │
 * ├──────────────────────┼────────────────────────────────────┼─────────┤
 * │ circuitBreaker       │ 熔断器：failureThreshold=5, 60s     │ L2+L4   │
 * │ tokenBudget          │ Token 预算：100k/20k/5k            │ L5      │
 * │ loopDetector         │ 循环检测器：maxRounds=10, max=3    │ L5      │
 * │ structuredErrorHandler│ 结构化错误处理器                    │ L4      │
 * │ fallbackChain        │ 降级链                              │ L2      │
 * └──────────────────────┴────────────────────────────────────┴─────────┘
 * </pre>
 * <p>
 * 注意：SecurityGuard、OutputMonitor、InputSanitizer 已通过 @Service/@Component
 * 注解自动注册为 Bean，无需在此重复声明。
 *
 * <pre>
 * 使用示例（依赖注入）：
 *   &#64;Autowired
 *   private CircuitBreaker circuitBreaker;
 *
 *   &#64;Autowired
 *   private TokenBudget tokenBudget;
 * </pre>
 */
@Slf4j
@Configuration
public class HarnessConfig {

    /**
     * 熔断器 Bean
     * <p>
     * 配置参数：
     * - failureThreshold = 5（连续失败 5 次触发熔断）
     * - resetTimeout = 60s（熔断后 60 秒进入半开状态）
     * - successThreshold = 2（半开状态连续成功 2 次恢复关闭）
     *
     * @return 熔断器实例
     */
    @Bean
    public CircuitBreaker circuitBreaker() {
        log.info("注册 Bean: CircuitBreaker (failureThreshold=5, resetTimeout=60s, successThreshold=2)");
        return new CircuitBreaker(5, 60, 2);
    }

    /**
     * Token 预算管理器 Bean
     * <p>
     * 配置参数：
     * - globalBudget = 100000（全局预算 10 万 Token）
     * - perSessionBudget = 20000（每会话预算 2 万 Token）
     * - perRoundBudget = 5000（每轮预算 5 千 Token）
     *
     * @return Token 预算管理器实例
     */
    @Bean
    public TokenBudget tokenBudget() {
        log.info("注册 Bean: TokenBudget (global=100000, perSession=20000, perRound=5000)");
        return new TokenBudget(100000, 20000, 5000);
    }

    /**
     * 循环检测器 Bean
     * <p>
     * 配置参数：
     * - maxRounds = 10（最大执行 10 轮，超过视为循环）
     * - maxSameAction = 3（连续相同操作 3 次视为循环）
     *
     * @return 循环检测器实例
     */
    @Bean
    public LoopDetector loopDetector() {
        log.info("注册 Bean: LoopDetector (maxRounds=10, maxSameAction=3)");
        return new LoopDetector(10, 3);
    }

    /**
     * 结构化错误处理器 Bean
     * <p>
     * 将异常分类为 TRANSIENT/SEMANTIC/STRUCTURAL 三种类型，
     * 生成包含修复指令的结构化错误信息。
     *
     * @return 结构化错误处理器实例
     */
    @Bean
    public StructuredErrorHandler structuredErrorHandler() {
        log.info("注册 Bean: StructuredErrorHandler");
        return new StructuredErrorHandler();
    }

    /**
     * 降级链 Bean
     * <p>
     * 主方案失败时按链降级，保证服务可用性。
     * 使用原始类型 FallbackChain（raw type）以兼容 ChatClient 等非泛型注入场景。
     *
     * @return 降级链实例
     */
    @Bean
    public FallbackChain<?> fallbackChain() {
        log.info("注册 Bean: FallbackChain");
        return new FallbackChain<>();
    }
}
