package com.charles.interview.arena.model.vo;

import lombok.Data;

@Data
public class UserVO {
    private Long id;
    private String username;
    private String nickname;
    private String avatar;
    private String role;
    // 没有 password / isDeleted / status
}