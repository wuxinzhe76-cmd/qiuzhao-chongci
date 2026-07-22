package com.charles.interview.arena.admin.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.charles.interview.arena.model.entity.QuestionBankQuestion;
import com.charles.interview.arena.model.vo.QuestionVO;

public interface QuestionBankQuestionService extends IService<QuestionBankQuestion> {

    /**
     * 添加题目到题库
     */
    Boolean addQuestionToBank(Long bankId, Long questionId, Long userId);

    /**
     * 批量添加题目到题库
     */
    Boolean batchAddQuestionsToBank(Long bankId, List<Long> questionIds, Long userId);

    /**
     * 从题库批量移除题目(物理删除)
     */
    Boolean batchRemoveQuestionsFromBank(Long bankId, List<Long> questionIds);

    /**
     * 查询题库下的所有题目
     */
    List<QuestionVO> listQuestionsByBankId(Long bankId);
}
