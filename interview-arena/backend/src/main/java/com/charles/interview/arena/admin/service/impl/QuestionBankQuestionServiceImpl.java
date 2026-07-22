package com.charles.interview.arena.admin.service.impl;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.charles.interview.arena.common.ErrorCode;
import com.charles.interview.arena.exception.ThrowUtils;
import com.charles.interview.arena.mapper.QuestionBankQuestionMapper;
import com.charles.interview.arena.mapper.QuestionMapper;
import com.charles.interview.arena.model.entity.Question;
import com.charles.interview.arena.model.entity.QuestionBankQuestion;
import com.charles.interview.arena.model.vo.QuestionVO;
import com.charles.interview.arena.admin.service.QuestionBankQuestionService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class QuestionBankQuestionServiceImpl extends ServiceImpl<QuestionBankQuestionMapper, QuestionBankQuestion>
        implements QuestionBankQuestionService {

    private final QuestionMapper questionMapper;

    @Override
    public Boolean addQuestionToBank(Long bankId, Long questionId, Long userId) {
        // 查重(联合唯一索引兜底,这里业务层先查更友好)
        long count = this.lambdaQuery()
                .eq(QuestionBankQuestion::getQuestionBankId, bankId)
                .eq(QuestionBankQuestion::getQuestionId, questionId)
                .count();
        ThrowUtils.throwIf(count > 0, ErrorCode.PARAMS_ERROR, "该题目已在题库中");

        QuestionBankQuestion relation = new QuestionBankQuestion();
        relation.setQuestionBankId(bankId);
        relation.setQuestionId(questionId);
        relation.setUserId(userId);
        boolean saved = this.save(relation);
        ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "添加题目到题库失败");
        return true;
    }

    @Override
    public Boolean batchAddQuestionsToBank(Long bankId, List<Long> questionIds, Long userId) {
        List<QuestionBankQuestion> relations = questionIds.stream().map(questionId -> {
            QuestionBankQuestion relation = new QuestionBankQuestion();
            relation.setQuestionBankId(bankId);
            relation.setQuestionId(questionId);
            relation.setUserId(userId);
            return relation;
        }).collect(Collectors.toList());
        boolean saved = this.saveBatch(relations);
        ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "批量添加题目失败");
        return true;
    }

    @Override
    public Boolean batchRemoveQuestionsFromBank(Long bankId, List<Long> questionIds) {
        boolean removed = this.lambdaUpdate()
                .eq(QuestionBankQuestion::getQuestionBankId, bankId)
                .in(QuestionBankQuestion::getQuestionId, questionIds)
                .remove();
        ThrowUtils.throwIf(!removed, ErrorCode.OPERATION_ERROR, "批量移除题目失败");
        return true;
    }

    @Override
    public List<QuestionVO> listQuestionsByBankId(Long bankId) {
        // 1. 查关联表,拿到题目ID列表
        List<Long> questionIds = this.lambdaQuery()
                .eq(QuestionBankQuestion::getQuestionBankId, bankId)
                .list()
                .stream()
                .map(QuestionBankQuestion::getQuestionId)
                .collect(Collectors.toList());
        if (questionIds.isEmpty()) {
            return List.of();
        }
        // 2. 批量查题目(逻辑删除自动过滤)
        List<Question> questions = questionMapper.selectBatchIds(questionIds);
        return questions.stream().map(question -> {
            QuestionVO vo = new QuestionVO();
            BeanUtils.copyProperties(question, vo);
            return vo;
        }).collect(Collectors.toList());
    }
}
