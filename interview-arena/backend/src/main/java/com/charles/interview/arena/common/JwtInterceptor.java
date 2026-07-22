package com.charles.interview.arena.common;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.charles.interview.arena.exception.BusinessException;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * JWT 拦截器（认证 + Sentinel 上下文注入）
 * <p>
 * 职责：
 * 1. URL 白名单：公开接口免登录
 * 2. JWT 认证：解析 token + Redis 校验
 * 3. Sentinel 上下文注入：把 userId 作为 origin 放入 Sentinel，后续可做接口级限流
 * <p>
 * 认证和流控分层：
 * - JwtInterceptor 管"你是谁"（认证）
 * - Sentinel 管"你能调多快"（流控）
 * - 两者通过 ContextUtil.enter(path, origin) 打通
 */
@Slf4j
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    /** Sentinel Entry，存在 ThreadLocal 中，afterCompletion 时 exit */
    private static final ThreadLocal<Entry> SENTINEL_ENTRY = new ThreadLocal<>();

    // 放行白名单(公开接口,无需登录)
    private static final List<String> WHITE_LIST = List.of(
            "/api/user/register",
            "/api/user/login",
            "/api/user/refresh",
            "/api/health",
            "/swagger-ui",
            "/v3/api-docs",
            // 题目浏览公开(像 LeetCode 一样,未登录也能看题)
            "/api/question/list/page/vo",
            "/api/question/get/vo",
            "/api/questionBank/list/page/vo",
            "/api/questionBank/get/vo",
            // RAG 搜索建议公开(首页搜索框,未登录也能用)
            "/api/rag/suggest"
    );

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) {
        String path = request.getRequestURI();

        // 1. 白名单放行
        boolean isWhiteListed = WHITE_LIST.stream().anyMatch(path::startsWith);

        // 2. 确定_origin（Sentinel 上下文中的调用方标识）
        String origin;
        if (isWhiteListed) {
            origin = "anonymous";
        } else {
            // 2a. 从请求头取 token
            String token = request.getHeader("Authorization");
            if (token == null || token.isEmpty()) {
                throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR.getCode(), "未提供 token");
            }

            // 2b. 解析 token
            Claims claims = JwtUtil.parseToken(token);
            if (claims == null) {
                throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR.getCode(), "token 无效或已过期");
            }

            // 2c. 校验 Redis 中 token 是否匹配
            Long userId = Long.parseLong(claims.getSubject());
            String redisKey = "access:" + userId;
            String redisToken = stringRedisTemplate.opsForValue().get(redisKey);
            if (redisToken == null || !redisToken.equals(token)) {
                throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR.getCode(), "token 已失效，请重新登录");
            }

            // 2d. 将 userId 存入 request，后续 Controller 可用
            request.setAttribute("userId", userId);
            origin = "user:" + userId;
        }

        // 3. Sentinel 限流：把 userId 注入上下文，对 HTTP 路径做 QPS 限流
        try {
            ContextUtil.enter(path, origin);
            Entry entry = SphU.entry(path);
            SENTINEL_ENTRY.set(entry);
        } catch (BlockException e) {
            ContextUtil.exit();
            log.warn("[Sentinel] HTTP 限流: path={}, origin={}, rule={}", path, origin, e.getRule());
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR.getCode(), "请求过于频繁，请稍后重试");
        }

        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                @NonNull Object handler, Exception ex) {
        // 清理 Sentinel 上下文（必须配对，否则 ThreadLocal 泄漏）
        Entry entry = SENTINEL_ENTRY.get();
        if (entry != null) {
            entry.exit();
            SENTINEL_ENTRY.remove();
        }
        ContextUtil.exit();
    }
}
