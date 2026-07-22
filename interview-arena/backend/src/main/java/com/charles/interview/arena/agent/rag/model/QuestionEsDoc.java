package com.charles.interview.arena.agent.rag.model;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.DateFormat;

import lombok.Data;

/**
 * 面试题 ES 文档（倒排索引，用于 BM25 关键词检索）
 * <p>
 * 八股映射：
 * - #6 混合检索：BM25 是打分函数不是索引结构，倒排索引才是
 * - IK 分词器：ik_max_word（索引时最大切分）+ ik_smart（查询时智能切分）
 */
@Data
@Document(indexName = "question")
public class QuestionEsDoc {

    @Id
    @Field(type = FieldType.Long)
    private Long questionId;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String title;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String content;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String answer;

    @Field(type = FieldType.Keyword)
    private String difficulty;

    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Keyword)
    private String type;

    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime createTime;
}
