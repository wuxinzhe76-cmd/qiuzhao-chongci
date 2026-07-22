package com.charles.interview.arena.agent.mcp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.model.entity.InterviewRecord;
import com.charles.interview.arena.model.entity.InterviewSession;
import com.charles.interview.arena.model.entity.Question;
import com.charles.interview.arena.agent.orchestration.interviewer.InterviewService;
import com.charles.interview.arena.admin.service.QuestionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.charles.interview.arena.mapper.InterviewRecordMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * MCP Server 工具集 - 把面试服务暴露为 MCP Server
 * <p>
 * 外部 AI Agent（如 Claude Desktop / Cursor / VS Code Copilot）
 * 可以通过 MCP 协议调用以下工具：
 * <p>
 * - get_interview_history：获取指定面试的完整对话记录
 * - get_question_detail：获取题目详情
 * - get_session_info：获取面试会话信息
 * - list_questions：列出题库中的题目
 * <p>
 * MCP Server 配置（application.yaml）：
 * <pre>
 * spring:
 *   ai:
 *     mcp:
 *       server:
 *         name: interview-arena-server
 *         version: 1.0.0
 *         type: SYNC
 *         protocol: SSE
 * </pre>
 * <p>
 * 外部客户端连接配置：
 * <pre>
 * {
 *   "mcpServers": {
 *     "interview-arena": {
 *       "url": "http://localhost:8080/sse"
 *     }
 *   }
 * }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpInterviewTools {

    private final InterviewService interviewService;
    private final QuestionService questionService;
    private final InterviewRecordMapper interviewRecordMapper;

    /**
     * 获取面试完整对话记录
     */
    @Tool(description = "获取指定面试会话的完整对话记录，包括面试官和候选人的所有对话")
    public List<InterviewRecord> getInterviewHistory(
            @ToolParam(description = "面试会话 ID") Long sessionId) {
        log.info("[MCP] getInterviewHistory: sessionId={}", sessionId);
        return interviewRecordMapper.selectList(
                new LambdaQueryWrapper<InterviewRecord>()
                        .eq(InterviewRecord::getSessionId, sessionId)
                        .orderByAsc(InterviewRecord::getRoundNum));
    }

    /**
     * 获取面试会话信息
     */
    @Tool(description = "获取面试会话的基本信息，包括用户ID、面试模式、状态等")
    public InterviewSession getSessionInfo(
            @ToolParam(description = "面试会话 ID") Long sessionId) {
        log.info("[MCP] getSessionInfo: sessionId={}", sessionId);
        return interviewService.getById(sessionId);
    }

    /**
     * 获取题目详情
     */
    @Tool(description = "根据题目ID获取题目的完整信息，包括标题、内容、参考答案")
    public Question getQuestionDetail(
            @ToolParam(description = "题目 ID") Long questionId) {
        log.info("[MCP] getQuestionDetail: questionId={}", questionId);
        return questionService.getById(questionId);
    }

    /**
     * 列出题库题目
     */
    @Tool(description = "列出题库中的题目，支持按关键词搜索和限制返回数量")
    public List<Question> listQuestions(
            @ToolParam(description = "搜索关键词（可选）") String keyword,
            @ToolParam(description = "返回数量限制，默认 10") Integer limit) {
        log.info("[MCP] listQuestions: keyword={}, limit={}", keyword, limit);
        int queryLimit = limit != null ? limit : 10;

        LambdaQueryWrapper<Question> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(Question::getTitle, keyword);
        }
        wrapper.last("LIMIT " + queryLimit);

        return questionService.list(wrapper);
    }
}
