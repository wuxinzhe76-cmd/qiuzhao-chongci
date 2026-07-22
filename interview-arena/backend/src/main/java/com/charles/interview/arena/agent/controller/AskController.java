package com.charles.interview.arena.agent.controller;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.charles.interview.arena.agent.orchestration.ask.QuickAskService;
import com.charles.interview.arena.agent.rag.model.RagChatResponse;
import com.charles.interview.arena.agent.rag.service.QuestionSearchService;
import com.charles.interview.arena.agent.rag.service.RagService;
import com.charles.interview.arena.agent.rag.service.UserKnowledgeBaseService;
import com.charles.interview.arena.common.BaseResponse;
import com.charles.interview.arena.common.ErrorCode;
import com.charles.interview.arena.common.ResultUtils;
import com.charles.interview.arena.exception.ThrowUtils;
import com.charles.interview.arena.model.dto.QuickAskDTO;
import com.charles.interview.arena.model.dto.RagChatDTO;
import com.charles.interview.arena.model.dto.SaveToKbDTO;
import com.charles.interview.arena.model.vo.QuickAskResponse;

import lombok.RequiredArgsConstructor;

/**
 * 询问助手（Ask Agent）入口
 * <p>
 * 与面试助手（InterviewController → InterviewOrchestrator）并列的第二个 Agent：
 * 用户在主页直接提问，走 RAG + Memory 智能路由链路。
 * <p>
 * 职责边界：本 Controller 只承载「询问」用例；
 * RAG 基础设施（向量库/ES 的 ETL 导入）的管理入口在 admin 包 RagAdminController。
 * <p>
 * URL 前缀 /api/rag，与 RagAdminController 一致。
 */
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class AskController {

    private final RagService ragService;
    private final QuestionSearchService questionSearchService;
    private final QuickAskService quickAskService;
    private final UserKnowledgeBaseService userKnowledgeBaseService;

    /**
     * 在线 RAG 问答：查询改写 → 混合检索 → Rerank → 去重重排 → 通义千问生成 + 引用标注（登录即可用）
     */
    @PostMapping("/chat")
    public BaseResponse<RagChatResponse> chat(@Valid @RequestBody RagChatDTO ragChatDTO, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR, "未登录");
        RagChatResponse response = ragService.ragChat(ragChatDTO.getMessage());
        return ResultUtils.success(response);
    }

    /**
     * 搜索栏 autocomplete：题目标题前缀匹配（ES match_phrase_prefix）
     */
    @GetMapping("/suggest")
    public BaseResponse<List<String>> suggest(
            @RequestParam String prefix,
            @RequestParam(defaultValue = "10") int limit) {
        List<String> suggestions = questionSearchService.suggest(prefix, limit);
        return ResultUtils.success(suggestions);
    }

    /**
     * Quick Ask 快速询问（蓝图 §5.5.6 + §5.4.5 RAG+Memory 智能路由）
     * <p>
     * RAG + Memory + MCP 联网混合流程：
     * 知识题 → RAG 检索题库；历史追问 → Memory 检索历次面试记录；
     * 混合场景两路并行；题库没有的 → MCP 联网搜索最新内容补充。
     */
    @PostMapping("/quick-ask")
    public BaseResponse<QuickAskResponse> quickAsk(@Valid @RequestBody QuickAskDTO dto,
                                                   HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR, "未登录");
        QuickAskResponse response = quickAskService.quickAsk(userId, dto.getQuery());
        return ResultUtils.success(response);
    }

    /**
     * 保存到个人知识库（蓝图 §5.5.6 save-to-kb）
     * <p>
     * 用户得到 Quick Ask 答案后，选择"存入我的知识库"，
     * 将 question + answer 向量化写入 Milvus（用户私有 collection）
     */
    @PostMapping("/save-to-kb")
    public BaseResponse<Boolean> saveToKb(@Valid @RequestBody SaveToKbDTO dto,
                                          HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR, "未登录");
        boolean success = userKnowledgeBaseService.saveToKb(userId, dto.getQuestion(), dto.getAnswer());
        return ResultUtils.success(success);
    }
}
