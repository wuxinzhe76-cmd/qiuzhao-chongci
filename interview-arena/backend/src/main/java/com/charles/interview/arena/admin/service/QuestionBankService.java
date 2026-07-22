package com.charles.interview.arena.admin.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.charles.interview.arena.model.dto.QuestionBankAddDTO;
import com.charles.interview.arena.model.dto.QuestionBankQueryDTO;
import com.charles.interview.arena.model.entity.QuestionBank;
import com.charles.interview.arena.model.vo.QuestionBankVO;

public interface QuestionBankService extends IService<QuestionBank> {

    /**
     * 创建题库
     */
    Long addQuestionBank(QuestionBankAddDTO dto, Long userId);

    /**
     * 更新题库
     */
    Boolean updateQuestionBank(QuestionBankAddDTO dto);

    /**
     * 删除题库(逻辑删除)
     */
    Boolean deleteQuestionBank(Long id);

    /**
     * 获取题库VO(脱敏)
     */
    QuestionBankVO getQuestionBankVO(Long id);

    /**
     * 分页查询题库列表
     */
    Page<QuestionBankVO> listQuestionBankVOByPage(QuestionBankQueryDTO dto);
}
