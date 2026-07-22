package com.charles.interview.arena.admin.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.charles.interview.arena.common.BaseResponse;
import com.charles.interview.arena.common.ResultUtils;
import com.charles.interview.arena.model.dto.QuestionBankAddDTO;
import com.charles.interview.arena.model.dto.QuestionBankQueryDTO;
import com.charles.interview.arena.model.vo.QuestionBankVO;
import com.charles.interview.arena.admin.service.QuestionBankService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/questionBank")
@RequiredArgsConstructor
public class QuestionBankController {

    private final QuestionBankService questionBankService;

    @PostMapping("/add")
    public BaseResponse<Long> add(@Valid @RequestBody QuestionBankAddDTO dto, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        Long id = questionBankService.addQuestionBank(dto, userId);
        return ResultUtils.success(id);
    }

    @PostMapping("/update")
    public BaseResponse<Boolean> update(@Valid @RequestBody QuestionBankAddDTO dto) {
        Boolean result = questionBankService.updateQuestionBank(dto);
        return ResultUtils.success(result);
    }

    @DeleteMapping("/delete/{id}")
    public BaseResponse<Boolean> delete(@PathVariable Long id) {
        Boolean result = questionBankService.deleteQuestionBank(id);
        return ResultUtils.success(result);
    }

    @GetMapping("/get/vo/{id}")
    public BaseResponse<QuestionBankVO> getVO(@PathVariable Long id) {
        QuestionBankVO vo = questionBankService.getQuestionBankVO(id);
        return ResultUtils.success(vo);
    }

    @PostMapping("/list/page/vo")
    public BaseResponse<Page<QuestionBankVO>> listPageVO(@RequestBody QuestionBankQueryDTO dto) {
        Page<QuestionBankVO> page = questionBankService.listQuestionBankVOByPage(dto);
        return ResultUtils.success(page);
    }
}
