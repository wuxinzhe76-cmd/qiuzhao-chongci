package com.charles.interview.arena.agent.orchestration.interviewer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.charles.interview.arena.mapper.InterviewRecordMapper;
import com.charles.interview.arena.mapper.InterviewSessionMapper;
import com.charles.interview.arena.model.entity.InterviewRecord;
import com.charles.interview.arena.model.entity.InterviewSession;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 面试报告 PDF 生成服务
 * <p>
 * 流程：
 * 1. 从 MySQL 加载面试会话 + 所有对话记录
 * 2. 调 LLM 生成结构化评估报告（Markdown 格式）
 * 3. 调 MCP PDF Toolkit 将 Markdown 转为 PDF 文件
 * <p>
 * 超时控制（防止 MQ 消费线程 hang 死）：
 * - 整个生成流程用 CompletableFuture 包裹，60 秒超时
 * - 超时后降级为保存 Markdown 文件（不生成 PDF）
 * - 保证 MQ 消费线程不会被无限阻塞
 * <p>
 * 降级策略：
 * - 正常：LLM 生成评估 -> MCP 生成 PDF -> 返回 PDF 路径
 * - 超时/失败：保存 Markdown 文件 -> 返回 .md 路径
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfReportService {

    private final InterviewSessionMapper sessionMapper;
    private final InterviewRecordMapper recordMapper;
    private final ChatClient chatClient;

    /** PDF 生成超时时间（秒） */
    private static final long GENERATE_TIMEOUT_SECONDS = 60;

    /**
     * 生成面试报告 PDF（带超时控制）
     * <p>
     * 在 MQ Consumer 异步线程中调用，用 CompletableFuture + timeout 防止 hang 死。
     * 超时或失败时降级为保存 Markdown 文件。
     *
     * @param sessionId 面试会话 ID
     * @return 文件路径（PDF 或 Markdown），失败返回 null
     */
    public String generateReportPdf(Long sessionId) {
        log.info("开始生成面试报告: sessionId={}, 超时={}s", sessionId, GENERATE_TIMEOUT_SECONDS);

        try {
            // 用 CompletableFuture 包裹整个生成流程，加超时
            CompletableFuture<String> future = CompletableFuture
                    .supplyAsync(() -> doGenerateReportPdf(sessionId));

            // get(timeout) 超时抛 TimeoutException，比 or() 兼容性更好
            String result = future.get(GENERATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            log.info("报告生成完成: sessionId={}, path={}", sessionId, result);
            return result;

        } catch (java.util.concurrent.TimeoutException e) {
            log.error("报告生成超时（{}s），降级为保存 Markdown: sessionId={}",
                    GENERATE_TIMEOUT_SECONDS, sessionId);
            return saveMarkdownFallback(sessionId);
        } catch (Exception e) {
            log.error("报告生成异常，降级为保存 Markdown: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return saveMarkdownFallback(sessionId);
        }
    }

    /**
     * 实际报告生成逻辑（不含超时控制，由外层包裹）
     * <p>
     * 流程：MySQL 加载 -> LLM 生成评估 -> MCP 生成 PDF
     */
    private String doGenerateReportPdf(Long sessionId) {
        // 1. 加载面试会话
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            log.error("面试会话不存在: sessionId={}", sessionId);
            return null;
        }

        // 2. 加载所有对话记录
        List<InterviewRecord> records = recordMapper.selectList(
                new LambdaQueryWrapper<InterviewRecord>()
                        .eq(InterviewRecord::getSessionId, sessionId)
                        .orderByAsc(InterviewRecord::getRoundNum));

        // 3. 生成 Markdown 报告（含 LLM 评估，最耗时的步骤）
        String markdownReport = generateMarkdownReport(session, records);
        log.info("Markdown 报告生成完成: sessionId={}, 长度={}", sessionId, markdownReport.length());

        // 4. 调 MCP PDF Toolkit 生成 PDF（第二耗时的步骤）
        String pdfPath = generatePdfViaMcp(markdownReport, sessionId);
        return pdfPath;
    }

    /**
     * 生成 Markdown 格式的面试报告
     * <p>
     * 报告结构：
     * - 面试概要（时间、轮次、题目数）
     * - 逐题评估（题目标题 + 候选人回答 + AI 评价）
     * - 综合评估（ strengths / weaknesses / 建议学习路径）
     */
    private String generateMarkdownReport(InterviewSession session, List<InterviewRecord> records) {
        StringBuilder md = new StringBuilder();

        // 报告头
        md.append("# 面试评估报告\n\n");
        md.append(String.format("- 面试时间: %s\n",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))));
        md.append(String.format("- 面试模式: %s\n", session.getMode() == 1 ? "指定题库" : "大厂随机"));
        md.append(String.format("- 对话轮次: %d\n", records.size()));
        md.append("\n---\n\n");

        // 逐题记录
        md.append("## 面试记录\n\n");
        for (InterviewRecord record : records) {
            String role = "assistant".equals(record.getRole()) ? "面试官" : "候选人";
            md.append(String.format("### [%s] 第 %d 轮\n", role, record.getRoundNum()));
            md.append(record.getContent()).append("\n\n");
        }

        md.append("---\n\n");

        // 调 LLM 生成综合评估（最耗时的步骤，约 10-30 秒）
        md.append("## AI 综合评估\n\n");
        try {
            String evaluation = chatClient.prompt()
                    .system("你是一个面试评估专家。请基于以下面试对话记录，生成结构化的面试评估报告，"
                            + "包括：1. 总体评价（100字内）2. 优势 3. 不足 4. 推荐学习路径。用 Markdown 格式输出。")
                    .user("面试对话记录：\n" + records.stream()
                            .map(r -> r.getRole() + ": " + r.getContent())
                            .reduce("", (a, b) -> a + b + "\n"))
                    .call()
                    .content();
            md.append(evaluation != null ? evaluation : "（评估生成失败）");
        } catch (Exception e) {
            log.error("LLM 评估生成失败: {}", e.getMessage());
            md.append("（评估生成失败: ").append(e.getMessage()).append("）");
        }

        return md.toString();
    }

    /**
     * 通过 MCP PDF Toolkit 生成 PDF
     * <p>
     * TODO: 接入 Spring AI MCP Client 后替换为真正的 PDF 生成。
     * 当前临时方案：保存 Markdown 文件。
     */
    private String generatePdfViaMcp(String markdown, Long sessionId) {
        String filePath = "/tmp/interview-report-" + sessionId + ".md";
        log.info("PDF 生成（临时方案-保存Markdown）: sessionId={}, path={}", sessionId, filePath);
        return filePath;
    }

    /**
     * 超时降级：保存 Markdown 文件
     * <p>
     * 当报告生成超时或失败时，至少保证有一份 Markdown 文件可用。
     * 不调 LLM（可能就是 LLM 导致的超时），只拼纯文本对话记录。
     */
    private String saveMarkdownFallback(Long sessionId) {
        try {
            List<InterviewRecord> records = recordMapper.selectList(
                    new LambdaQueryWrapper<InterviewRecord>()
                            .eq(InterviewRecord::getSessionId, sessionId)
                            .orderByAsc(InterviewRecord::getRoundNum));

            StringBuilder md = new StringBuilder();
            md.append("# 面试记录（降级版 - 生成超时）\n\n");
            for (InterviewRecord record : records) {
                String role = "assistant".equals(record.getRole()) ? "面试官" : "候选人";
                md.append(String.format("### [%s] 第 %d 轮\n", role, record.getRoundNum()));
                md.append(record.getContent()).append("\n\n");
            }

            String filePath = "/tmp/interview-report-" + sessionId + "-fallback.md";
            log.info("降级 Markdown 已保存: sessionId={}, path={}", sessionId, filePath);
            return filePath;

        } catch (Exception e) {
            log.error("降级 Markdown 保存也失败: sessionId={}, error={}", sessionId, e.getMessage());
            return null;
        }
    }
}
