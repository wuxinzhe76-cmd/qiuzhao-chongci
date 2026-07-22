package com.charles.interview.arena.admin.controller;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.charles.interview.arena.admin.service.UserService;
import com.charles.interview.arena.agent.rag.service.PdfImportService;
import com.charles.interview.arena.agent.rag.service.RagService;
import com.charles.interview.arena.common.BaseResponse;
import com.charles.interview.arena.common.ErrorCode;
import com.charles.interview.arena.common.ResultUtils;
import com.charles.interview.arena.exception.ThrowUtils;
import com.charles.interview.arena.model.entity.User;

import lombok.RequiredArgsConstructor;

/**
 * RAG 基础设施管理入口（仅管理员）
 * <p>
 * 职责隔离：ETL 离线索引属于向量库/ES 的运维操作，
 * 与面向用户的询问助手（AskController）分离。
 * <p>
 * URL 保持 /rag/import 不变，兼容现有调用。
 */
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagAdminController {

    private final RagService ragService;
    private final PdfImportService pdfImportService;
    private final UserService userService;

    /**
     * ETL 离线索引：将面试题导入 Milvus 向量库 + ES 倒排索引（仅管理员）
     */
    @PostMapping("/import")
    public BaseResponse<Integer> importQuestionsToVectorStore(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        User user = userService.getById(userId);
        ThrowUtils.throwIf(user == null || !"admin".equals(user.getRole()),
                ErrorCode.NO_AUTH_ERROR, "无权限，仅管理员可操作");
        int count = ragService.importQuestionsToVectorStore();
        return ResultUtils.success(count);
    }

    /**
     * PDF 参考答案导入：解析 PDF -> 精细切分 -> 向量化入库 Milvus + ES（仅管理员）
     * <p>
     * 数据源：interview.pdf.source-dir 配置的目录下的所有 PDF 文件
     */
    @PostMapping("/import-pdf")
    public BaseResponse<Integer> importPdfToVectorStore(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        User user = userService.getById(userId);
        ThrowUtils.throwIf(user == null || !"admin".equals(user.getRole()),
                ErrorCode.NO_AUTH_ERROR, "无权限，仅管理员可操作");
        int count = pdfImportService.importPdfToVectorStore();
        return ResultUtils.success(count);
    }
}
