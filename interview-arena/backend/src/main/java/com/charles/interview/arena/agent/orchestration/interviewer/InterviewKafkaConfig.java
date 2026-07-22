package com.charles.interview.arena.agent.orchestration.interviewer;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 面试 Kafka 配置
 * <p>
 * Topic 设计:
 * - interview-report-events: 面试结束事件(报告生成 + 记忆整合)
 * - interview-question-events: 题目级事件(向量化 + 实时画像更新,后续迭代)
 */
@Configuration
public class InterviewKafkaConfig {

    /** 面试报告事件 Topic */
    public static final String REPORT_TOPIC = "interview-report-events";

    /** 题目级事件 Topic(后续迭代) */
    public static final String QUESTION_TOPIC = "interview-question-events";

    /**
     * 面试报告 Topic(3 分区,持久化)
     */
    @Bean
    public NewTopic reportTopic() {
        return TopicBuilder.name(REPORT_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * 题目级事件 Topic(后续迭代)
     */
    @Bean
    public NewTopic questionTopic() {
        return TopicBuilder.name(QUESTION_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
