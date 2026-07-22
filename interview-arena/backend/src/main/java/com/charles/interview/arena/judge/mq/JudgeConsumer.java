package com.charles.interview.arena.judge.mq;

import java.io.IOException;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.judge.enums.JudgeStatus;
import com.charles.interview.arena.judge.service.JudgeService;
import com.charles.interview.arena.judge.service.SubmissionService;
import com.charles.interview.arena.model.entity.Submission;
import com.rabbitmq.client.Channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 判题消息消费者
 * 监听 judge.queue,收到消息后异步执行判题
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JudgeConsumer {

    private final JudgeService judgeService;
    private final SubmissionService submissionService;

    @RabbitListener(queues = JudgeMqConfig.JUDGE_QUEUE)
    public void consume(Long submissionId, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        try {
            log.info("收到判题消息, submissionId={}", submissionId);
            judgeService.doJudge(submissionId);
            // 判题成功,手动确认
            channel.basicAck(deliveryTag, false);
            log.info("判题完成, submissionId={}", submissionId);
        } catch (Exception e) {
            log.error("判题失败, submissionId={}", submissionId, e);
            // 判题失败,更新 submission 状态为 RE
            try {
                Submission submission = submissionService.getById(submissionId);
                if (submission != null) {
                    submission.setStatus(JudgeStatus.RUNTIME_ERROR.getValue());
                    submissionService.updateById(submission);
                }
            } catch (Exception ex) {
                log.error("更新失败状态异常", ex);
            }
            // 消息处理失败,确认消费(不重新入队,防止死循环)
            // 如果想重试,可以改为 channel.basicNack(deliveryTag, false, true)
            channel.basicAck(deliveryTag, false);
        }
    }
}
