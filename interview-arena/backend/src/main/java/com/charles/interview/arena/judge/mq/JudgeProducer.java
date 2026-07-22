package com.charles.interview.arena.judge.mq;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 判题消息生产者
 * Controller 提交代码后调用,发送 submissionId 到队列
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JudgeProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 发送判题消息
     * @param submissionId 提交记录ID
     */
    public void sendJudgeMessage(Long submissionId) {
        rabbitTemplate.convertAndSend(
                JudgeMqConfig.JUDGE_QUEUE,  // 队列名
                submissionId                // 消息内容(只发ID,消费者自己查数据库)
        );
        log.info("判题消息已发送, submissionId={}", submissionId);
    }
}
