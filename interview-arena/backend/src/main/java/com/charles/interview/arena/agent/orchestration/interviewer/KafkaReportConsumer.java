package com.charles.interview.arena.agent.orchestration.interviewer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.memory.MemoryFacade;
import com.charles.interview.arena.model.entity.InterviewSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * 面试报告 Kafka 消费者(替代 RabbitMQ Consumer)
 * <p>
 * 监听 interview-report-events Topic,收到消息后异步执行:
 * 1. 触发记忆整合(工作->情景->语义,更新用户画像)
 * 2. 生成面试报告 PDF
 * <p>
 * Kafka 优势:
 * - 消息持久化(log),不怕消费者宕机
 * - 多消费者组可并行消费(报告/画像/学习路径独立)
 * - 高吞吐,适合多用户并发面试
 */
@Component
@RequiredArgsConstructor
public class KafkaReportConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaReportConsumer.class);

    private final InterviewService interviewService;
    private final MemoryFacade memoryFacade;
    private final PdfReportService pdfReportService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = InterviewKafkaConfig.REPORT_TOPIC, groupId = "interview-report-group")
    public void consume(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            Long sessionId = node.path("sessionId").asLong();
            Long userId = node.path("userId").asLong();

            log.info("收到面试报告 Kafka 事件: sessionId={}, userId={}", sessionId, userId);

            // 1. 触发记忆整合(工作->情景->语义升级)
            InterviewSession session = interviewService.getById(sessionId);
            if (session != null) {
                memoryFacade.consolidate(sessionId, session.getUserId());
            }

            // 2. 生成面试报告 PDF
            String pdfPath = pdfReportService.generateReportPdf(sessionId);
            if (pdfPath != null) {
                log.info("面试报告生成完成: sessionId={}, pdfPath={}", sessionId, pdfPath);
            } else {
                log.warn("面试报告生成失败: sessionId={}", sessionId);
            }

        } catch (Exception e) {
            log.error("面试报告 Kafka 消费失败: message={}", message, e);
        }
    }
}
