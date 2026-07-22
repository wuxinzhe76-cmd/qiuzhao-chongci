package com.charles.interview.arena.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Cache 配置
 * <p>
 * 启用 @Cacheable 注解支持，缓存后端为 Redis（Spring Boot 自动配置）。
 * <p>
 * 缓存策略：
 * - getQuestionDetail：题目详情缓存 30 分钟（读多写少）
 * - 面试会话数据不缓存（实时性要求高）
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
