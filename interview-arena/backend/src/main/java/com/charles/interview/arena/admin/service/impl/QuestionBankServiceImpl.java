package com.charles.interview.arena.admin.service.impl;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.charles.interview.arena.common.ErrorCode;
import com.charles.interview.arena.exception.ThrowUtils;
import com.charles.interview.arena.mapper.QuestionBankMapper;
import com.charles.interview.arena.model.dto.QuestionBankAddDTO;
import com.charles.interview.arena.model.dto.QuestionBankQueryDTO;
import com.charles.interview.arena.model.entity.QuestionBank;
import com.charles.interview.arena.model.vo.QuestionBankVO;
import com.charles.interview.arena.admin.service.QuestionBankService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class QuestionBankServiceImpl extends ServiceImpl<QuestionBankMapper, QuestionBank>
        implements QuestionBankService {

    @Override
    public Long addQuestionBank(QuestionBankAddDTO dto, Long userId) {
        QuestionBank questionBank = new QuestionBank();
        BeanUtils.copyProperties(dto, questionBank);
        questionBank.setUserId(userId);
        boolean saved = this.save(questionBank);
        ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "题库创建失败");
        return questionBank.getId();
    }

    @Override
    public Boolean updateQuestionBank(QuestionBankAddDTO dto) {
        QuestionBank questionBank = new QuestionBank();
        BeanUtils.copyProperties(dto, questionBank);
        boolean updated = this.updateById(questionBank);
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "题库更新失败");
        return true;
    }

    @Override
    public Boolean deleteQuestionBank(Long id) {
        boolean removed = this.removeById(id);
        ThrowUtils.throwIf(!removed, ErrorCode.OPERATION_ERROR, "题库删除失败");
        return true;
    }

    @Override
    public QuestionBankVO getQuestionBankVO(Long id) {
        QuestionBank questionBank = this.getById(id);
        ThrowUtils.throwIf(questionBank == null, ErrorCode.NOT_FOUND_ERROR, "题库不存在");
        QuestionBankVO vo = new QuestionBankVO();
        BeanUtils.copyProperties(questionBank, vo);
        return vo;
    }

    @Override
    public Page<QuestionBankVO> listQuestionBankVOByPage(QuestionBankQueryDTO dto) {
    // 1. 构建查询条件(title 模糊查询,空值不拼条件)
    QueryWrapper<QuestionBank> queryWrapper = new QueryWrapper<>();
    queryWrapper.like(StringUtils.isNotBlank(dto.getTitle()), "title", dto.getTitle());

    // 2. 分页查询
    Page<QuestionBank> page = this.page(
        new Page<>(dto.getCurrent(), dto.getPageSize()),
        queryWrapper
    );

    // 3. Page<QuestionBank> 转 Page<QuestionBankVO>
    Page<QuestionBankVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
    List<QuestionBankVO> voList = page.getRecords()
                                        .stream()
                                        .map(questionBank -> {
                                        QuestionBankVO vo = new QuestionBankVO();
                                        BeanUtils.copyProperties(questionBank, vo);
                                        return vo;})
                                        .collect(Collectors.toList());
                                        voPage.setRecords(voList);
    return voPage;
    }
}
