package com.charles.interview.arena.admin.controller;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.charles.interview.arena.common.BaseResponse;
import com.charles.interview.arena.common.ResultUtils;
import com.charles.interview.arena.model.dto.QuestionAddDTO;
import com.charles.interview.arena.model.dto.QuestionQueryDTO;
import com.charles.interview.arena.model.vo.QuestionVO;
import com.charles.interview.arena.admin.service.QuestionBankQuestionService;
import com.charles.interview.arena.admin.service.QuestionService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/question")
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionService questionService;
    private final QuestionBankQuestionService questionBankQuestionService;

    @PostMapping("/add")
    public BaseResponse<Long> add(@Valid @RequestBody QuestionAddDTO dto, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        Long id = questionService.addQuestion(dto, userId);
        return ResultUtils.success(id);
    }

    @PostMapping("/update")
    public BaseResponse<Boolean> update(@Valid @RequestBody QuestionAddDTO dto) {
        Boolean result = questionService.updateQuestion(dto);
        return ResultUtils.success(result);
    }

    @DeleteMapping("/delete/{id}")
    public BaseResponse<Boolean> delete(@PathVariable Long id) {
        Boolean result = questionService.deleteQuestion(id);
        return ResultUtils.success(result);
    }

    @GetMapping("/get/vo/{id}")
    public BaseResponse<QuestionVO> getVO(@PathVariable Long id) {
        QuestionVO vo = questionService.getQuestionVO(id);
        return ResultUtils.success(vo);
    }

    @PostMapping("/list/page/vo")
    public BaseResponse<Page<QuestionVO>> listPageVO(@RequestBody QuestionQueryDTO dto) {
        Page<QuestionVO> page = questionService.listQuestionVOByPage(dto);
        return ResultUtils.success(page);
    }

    // ========== 题库-题目关联管理 ==========

    @PostMapping("/bank/add")
    public BaseResponse<Boolean> addToBank(@RequestParam Long bankId, @RequestParam Long questionId,
                                           HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        Boolean result = questionBankQuestionService.addQuestionToBank(bankId, questionId, userId);
        return ResultUtils.success(result);
    }

    @PostMapping("/bank/batchAdd")
    public BaseResponse<Boolean> batchAddToBank(@RequestParam Long bankId, @RequestBody List<Long> questionIds,
                                                HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        Boolean result = questionBankQuestionService.batchAddQuestionsToBank(bankId, questionIds, userId);
        return ResultUtils.success(result);
    }

    @PostMapping("/bank/batchRemove")
    public BaseResponse<Boolean> batchRemoveFromBank(@RequestParam Long bankId,
                                                     @RequestBody List<Long> questionIds) {
        Boolean result = questionBankQuestionService.batchRemoveQuestionsFromBank(bankId, questionIds);
        return ResultUtils.success(result);
    }

    @GetMapping("/bank/list/{bankId}")
    public BaseResponse<List<QuestionVO>> listByBankId(@PathVariable Long bankId) {
        List<QuestionVO> list = questionBankQuestionService.listQuestionsByBankId(bankId);
        return ResultUtils.success(list);
    }
}
