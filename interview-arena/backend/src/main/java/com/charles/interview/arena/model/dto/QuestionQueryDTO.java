package com.charles.interview.arena.model.dto;

import java.util.List;

import lombok.Data;

@Data
public class QuestionQueryDTO {

    /**
     * 题目标题(模糊查询)
     */
    private String title;

    /**
     * 标签筛选(JSON_CONTAINS,支持多个标签)
     */
    private List<String> tags;

    /**
     * 题目类型:PROGRAMMING/CHOICE/FILL_IN
     */
    private String type;

    /**
     * 难度:EASY/MEDIUM/HARD
     */
    private String difficulty;

    /**
     * 当前页码(默认1)
     */
    private int current = 1;

    /**
     * 每页条数(默认10)
     */
    private int pageSize = 10;
}
