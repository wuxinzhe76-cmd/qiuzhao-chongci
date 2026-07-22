package com.charles.interview.arena.agent.harness.common;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 降级链（Fallback Chain）
 * <p>
 * 关联 Harness 层：L2 容错层
 * 关联八股题号：Day04 #7 死循环、Day05 降级策略与容错
 * <p>
 * 核心职责：
 * 当主方案失败时，按预定义的降级链依次尝试备选方案，直到有一个成功或全部失败。
 * 保证 Agent 在外部依赖不可用时仍能提供降级服务，而不是直接报错。
 * <p>
 * 降级链示例：
 * <pre>
 * 主方案：调用 GPT-4 生成回答
 *   ↓ 失败
 * 降级1：调用通义千问生成回答
 *   ↓ 失败
 * 降级2：从缓存返回上次相似问题的回答
 *   ↓ 失败
 * 降级3：返回预设的兜底回复
 * </pre>
 *
 * <pre>
 * 使用示例：
 *   FallbackChain&lt;String&gt; chain = new FallbackChain&lt;&gt;();
 *   chain.addFallback(() -> callGpt4(prompt));      // 主方案
 *   chain.addFallback(() -> callQwen(prompt));      // 降级1
 *   chain.addFallback(() -> getFromCache(prompt));   // 降级2
 *   chain.addFallback(() -> "服务暂时不可用");        // 兜底
 *   String result = chain.execute();
 * </pre>
 *
 * @param <T> 降级链返回类型
 */
@Slf4j
public class FallbackChain<T> {

    /** 降级方案列表，按优先级从高到低排列 */
    private final List<Supplier<T>> fallbacks = new ArrayList<>();

    /**
     * 添加降级方案
     * <p>
     * 第一个添加的是主方案，后续添加的是降级方案，按添加顺序依次尝试。
     *
     * @param fallback 降级方案（Supplier）
     * @return 当前 FallbackChain 实例（支持链式调用）
     */
    public FallbackChain<T> addFallback(Supplier<T> fallback) {
        fallbacks.add(fallback);
        return this;
    }

    /**
     * 注册主方案对象（直接传入对象，内部包装为 Supplier）
     * <p>
     * 兼容已有调用方直接传入对象（如 ChatClient）而非 Supplier 的场景。
     *
     * @param primary 主方案对象
     * @return 当前 FallbackChain 实例（支持链式调用）
     */
    public FallbackChain<T> registerPrimary(T primary) {
        fallbacks.add(0, () -> primary);
        log.info("降级链：已注册主方案: {}", primary.getClass().getSimpleName());
        return this;
    }

    /**
     * 注册降级方案对象（直接传入对象，内部包装为 Supplier）
     * <p>
     * 兼容已有调用方直接传入对象（如 ChatClient）而非 Supplier 的场景。
     *
     * @param fallback 降级方案对象
     * @return 当前 FallbackChain 实例（支持链式调用）
     */
    public FallbackChain<T> registerFallback(T fallback) {
        fallbacks.add(() -> fallback);
        log.info("降级链：已注册降级方案: {}", fallback.getClass().getSimpleName());
        return this;
    }

    /**
     * 执行降级链
     * <p>
     * 从主方案开始依次尝试，如果某个方案成功（不抛异常且返回非 null），立即返回结果。
     * 如果所有方案都失败，抛出最后一个异常。
     *
     * @return 第一个成功的方案的结果
     * @throws RuntimeException 如果所有方案都失败
     */
    public T execute() {
        if (fallbacks.isEmpty()) {
            throw new IllegalStateException("降级链为空，没有可执行的方案");
        }

        Exception lastException = null;
        for (int i = 0; i < fallbacks.size(); i++) {
            Supplier<T> fallback = fallbacks.get(i);
            String label = i == 0 ? "主方案" : "降级方案" + i;
            try {
                T result = fallback.get();
                if (result != null) {
                    if (i > 0) {
                        log.info("降级链：{} 成功（第 {} 个方案）", label, i + 1);
                    }
                    return result;
                }
                log.warn("降级链：{} 返回 null，尝试下一个方案", label);
            } catch (Exception e) {
                lastException = e;
                log.warn("降级链：{} 失败: {}，尝试下一个方案", label, e.getMessage());
            }
        }

        log.error("降级链：所有 {} 个方案均失败", fallbacks.size());
        if (lastException != null) {
            throw new RuntimeException("所有降级方案均失败", lastException);
        }
        throw new RuntimeException("所有降级方案均失败（返回 null）");
    }

    /**
     * 执行降级链（指定主方案）
     * <p>
     * 便捷方法：传入主方案 Supplier，与已添加的降级方案组合执行。
     *
     * @param primary 主方案
     * @return 第一个成功的方案的结果
     */
    public T execute(Supplier<T> primary) {
        List<Supplier<T>> all = new ArrayList<>();
        all.add(primary);
        all.addAll(fallbacks);

        Exception lastException = null;
        for (int i = 0; i < all.size(); i++) {
            Supplier<T> supplier = all.get(i);
            String label = i == 0 ? "主方案" : "降级方案" + i;
            try {
                T result = supplier.get();
                if (result != null) {
                    if (i > 0) {
                        log.info("降级链：{} 成功（第 {} 个方案）", label, i + 1);
                    }
                    return result;
                }
            } catch (Exception e) {
                lastException = e;
                log.warn("降级链：{} 失败: {}", label, e.getMessage());
            }
        }

        log.error("降级链：所有方案均失败");
        if (lastException != null) {
            throw new RuntimeException("所有方案均失败", lastException);
        }
        throw new RuntimeException("所有方案均失败（返回 null）");
    }

    /**
     * 获取降级链中的方案数量
     *
     * @return 方案数量
     */
    public int size() {
        return fallbacks.size();
    }
}
