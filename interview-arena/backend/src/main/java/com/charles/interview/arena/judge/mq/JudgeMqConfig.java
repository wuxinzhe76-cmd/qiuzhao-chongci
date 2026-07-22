package com.charles.interview.arena.judge.mq;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置
 * 声明判题队列(持久化,服务器重启不丢消息)
 */
@Configuration
public class JudgeMqConfig {

    public static final String JUDGE_QUEUE = "judge.queue";

    @Bean
    public Queue judgeQueue() {
        // durable=true: 持久化队列, RabbitMQ 重启后队列不丢失
        return new Queue(JUDGE_QUEUE, true);
    }
}
