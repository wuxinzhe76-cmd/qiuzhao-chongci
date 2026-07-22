package com.charles.interview.arena.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO {

    /**
     * 访问令牌（2小时有效）
     */
    private String accessToken;

    /**
     * 刷新令牌（7天有效，用于无感刷新）
     */
    private String refreshToken;

    /**
     * 用户基本信息
     */
    private UserVO userInfo;
}