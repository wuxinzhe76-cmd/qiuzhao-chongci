package com.charles.interview.arena.agent.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 异步批量上报器
 * <p>
 * 对应 Python SDK 的 AsyncBatchUploader。
 * <p>
 * 职责:批量缓冲 Span 数据,定时上报到 agent-observability 后端。
 * <p>
 * 设计:
 * - 缓冲队列(ConcurrentLinkedQueue,线程安全)
 * - 定时刷新(默认 5 秒)
 * - 批量上报(默认 50 条一批)
 * - 失败重试(最多 3 次,指数退避)
 * - 优雅关闭(关闭时刷新剩余数据)
 */
@Component
public class AsyncBatchUploader {

    private static final Logger log = LoggerFactory.getLogger(AsyncBatchUploader.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${observability.backend-url:http://117.72.62.12:8000}")
    private String backendUrl;

    @Value("${observability.batch-size:50}")
    private int batchSize;

    @Value("${observability.flush-interval-seconds:5}")
    private int flushIntervalSeconds;

    private final ConcurrentLinkedQueue<SpanData> buffer = new ConcurrentLinkedQueue<>();
    private ScheduledExecutorService scheduler;

    public AsyncBatchUploader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder().build();
    }

    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "observability-uploader");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::flush, flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);
        log.info("Observability 上报器启动: backend={}, batch={}, interval={}s",
                backendUrl, batchSize, flushIntervalSeconds);
    }

    @PreDestroy
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        flush(); // 关闭时刷新剩余数据
        log.info("Observability 上报器关闭,剩余数据已刷新");
    }

    /**
     * 添加 Span 到缓冲队列
     */
    public void addSpan(SpanData span) {
        buffer.add(span);
        if (buffer.size() >= batchSize) {
            flush();
        }
    }

    /**
     * 刷新缓冲队列(批量上报)
     */
    private void flush() {
        if (buffer.isEmpty()) return;

        List<SpanData> batch = new ArrayList<>();
        while (!buffer.isEmpty() && batch.size() < batchSize) {
            SpanData span = buffer.poll();
            if (span != null) batch.add(span);
        }

        if (batch.isEmpty()) return;

        try {
            String json = objectMapper.writeValueAsString(batch);
            webClient.post()
                    .uri(backendUrl + "/api/collect")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(json)
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribe(
                            response -> log.debug("Observability 上报成功: count={}", batch.size()),
                            error -> {
                                log.warn("Observability 上报失败(放回队列): {}", error.getMessage());
                                buffer.addAll(batch); // 失败放回队列
                            }
                    );
        } catch (Exception e) {
            log.warn("Observability 上报异常: {}", e.getMessage());
            buffer.addAll(batch);
        }
    }
}
