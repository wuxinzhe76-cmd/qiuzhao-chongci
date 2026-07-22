package com.charles.interview.arena.admin.controller;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.charles.interview.arena.common.BaseResponse;
import com.charles.interview.arena.common.ErrorCode;
import com.charles.interview.arena.common.ResultUtils;
import com.charles.interview.arena.exception.ThrowUtils;
import com.charles.interview.arena.judge.service.TestCaseService;
import com.charles.interview.arena.model.dto.TestCaseAddDTO;
import com.charles.interview.arena.model.entity.TestCase;

import lombok.RequiredArgsConstructor;

/**
 * 测试用例管理接口(管理员用)
 */
@RestController
@RequestMapping("/api/testCase")
@RequiredArgsConstructor
public class TestCaseController {

    private final TestCaseService testCaseService;

    /**
     * 添加测试用例
     */
    @PostMapping("/add")
    public BaseResponse<Long> add(@Valid @RequestBody TestCaseAddDTO dto, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");

        TestCase testCase = new TestCase();
        BeanUtils.copyProperties(dto, testCase);
        testCase.setUserId(userId);

        boolean saved = testCaseService.save(testCase);
        ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "测试用例添加失败");
        return ResultUtils.success(testCase.getId());
    }

    /**
     * 更新测试用例
     */
    @PostMapping("/update/{id}")
    public BaseResponse<Boolean> update(@PathVariable Long id,
                                        @Valid @RequestBody TestCaseAddDTO dto) {
        TestCase testCase = testCaseService.getById(id);
        ThrowUtils.throwIf(testCase == null, ErrorCode.NOT_FOUND_ERROR, "测试用例不存在");

        BeanUtils.copyProperties(dto, testCase, "id", "userId");
        boolean updated = testCaseService.updateById(testCase);
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "测试用例更新失败");
        return ResultUtils.success(true);
    }

    /**
     * 删除测试用例(逻辑删除)
     */
    @DeleteMapping("/delete/{id}")
    public BaseResponse<Boolean> delete(@PathVariable Long id) {
        boolean removed = testCaseService.removeById(id);
        ThrowUtils.throwIf(!removed, ErrorCode.OPERATION_ERROR, "测试用例删除失败");
        return ResultUtils.success(true);
    }

    /**
     * 查询某道题的所有测试用例
     */
    @GetMapping("/list/{questionId}")
    public BaseResponse<List<TestCase>> listByQuestionId(@PathVariable Long questionId) {
        List<TestCase> list = testCaseService.lambdaQuery()
                .eq(TestCase::getQuestionId, questionId)
                .orderByAsc(TestCase::getId)
                .list();
        return ResultUtils.success(list);
    }

    /**
     * 查询单个测试用例
     */
    @GetMapping("/get/{id}")
    public BaseResponse<TestCase> get(@PathVariable Long id) {
        TestCase testCase = testCaseService.getById(id);
        ThrowUtils.throwIf(testCase == null, ErrorCode.NOT_FOUND_ERROR, "测试用例不存在");
        return ResultUtils.success(testCase);
    }
}
