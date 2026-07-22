package com.charles.interview.arena.model.dto;

import lombok.Data;

@Data
public class QuestionBankQueryDTO {

    /**
     * 题库名称(模糊查询)
     */
    private String title;

    /**
     * 当前页码(默认1)
     */
    private int current = 1;

    /**
     * 每页条数(默认10)
     */
    private int pageSize = 10;
}
