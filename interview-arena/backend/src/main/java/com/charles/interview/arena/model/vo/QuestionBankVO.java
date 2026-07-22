package com.charles.interview.arena.model.vo;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class QuestionBankVO {

    private Long id;
    private String title;
    private String description;
    private String picture;
    private Long userId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
