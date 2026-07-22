package com.charles.interview.arena.common;

import java.util.Date;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

public class JwtUtil {

    private static final String SECRET = "interview-arena-secret-key-must-be-at-least-32-characters-long";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes());

    /**
     * 生成 accessToken（2 小时）
     */
    public static String generateAccessToken(Long userId, String username) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("type", "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 7200 * 1000))
                .signWith(KEY)
                .compact();
    }

    /**
     * 生成 refreshToken（7 天）
     */
    public static String generateRefreshToken(Long userId, String username) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("type", "refresh")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 7 * 24 * 3600 * 1000))
                .signWith(KEY)
                .compact();
    }

    /**
     * 解析 token，返回 Claims（含 userId、username、type）
     * 失败返回 null
     */
    public static Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(KEY)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 token 中获取 userId
     */
    public static Long getUserId(String token) {
        Claims claims = parseToken(token);
        if (claims == null) return null;
        return Long.parseLong(claims.getSubject());
    }

    /**
     * 判断 token 是否是 refreshToken
     */
    public static boolean isRefreshToken(String token) {
        Claims claims = parseToken(token);
        if (claims == null) return false;
        return "refresh".equals(claims.get("type"));
    }
}