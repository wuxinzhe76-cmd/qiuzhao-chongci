package com.charles.interview.arena.agent.orchestration.interviewer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;

/**
 * 面试报告 Kafka 生产者(替代 RabbitMQ Producer)
 * <p>
 * 面试结束时发送事件到 Kafka Topic,
 * 消费者异步生成报告 + 记忆整合 + 学习路径。
 */
@Component
@RequiredArgsConstructor
public class KafkaReportProducer {

    private static final Logger log = LoggerFactory.getLogger(KafkaReportProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 发送面试报告生成事件
     *
     * @param sessionId 面试会话 ID
     * @param userId    用户 ID
     */
    public void sendInterviewReportEvent(Long sessionId, Long userId) {
        try {
            ObjectNode message = objectMapper.createObjectNode();
            message.put("sessionId", sessionId);
            message.put("userId", userId);
            message.put("timestamp", System.currentTimeMillis());

            kafkaTemplate.send(InterviewKafkaConfig.REPORT_TOPIC,
                    String.valueOf(sessionId),
                    objectMapper.writeValueAsString(message));

            log.info("面试报告事件已发送到 Kafka: sessionId={}, userId={}", sessionId, userId);
        } catch (Exception e) {
            log.error("Kafka 消息发送失败: sessionId={}", sessionId, e);
        }
    }
}
