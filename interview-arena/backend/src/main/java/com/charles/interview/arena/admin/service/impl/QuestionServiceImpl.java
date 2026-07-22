package com.charles.interview.arena.admin.service.impl;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.charles.interview.arena.common.ErrorCode;
import com.charles.interview.arena.exception.ThrowUtils;
import com.charles.interview.arena.mapper.QuestionMapper;
import com.charles.interview.arena.model.dto.QuestionAddDTO;
import com.charles.interview.arena.model.dto.QuestionQueryDTO;
import com.charles.interview.arena.model.entity.Question;
import com.charles.interview.arena.model.vo.QuestionVO;
import com.charles.interview.arena.agent.rag.event.QuestionChangedEvent;
import com.charles.interview.arena.agent.rag.event.QuestionChangedEvent.Action;
import com.charles.interview.arena.admin.service.QuestionService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class QuestionServiceImpl extends ServiceImpl<QuestionMapper, Question> implements QuestionService {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public Long addQuestion(QuestionAddDTO dto, Long userId) {
        Question question = new Question();
        BeanUtils.copyProperties(dto, question);
        question.setUserId(userId);
        // 设置默认值(DTO 未传时)
        if (question.getType() == null) {
            question.setType("PROGRAMMING");
        }
        if (question.getDifficulty() == null) {
            question.setDifficulty("MEDIUM");
        }
        if (question.getTimeLimit() == null) {
            question.setTimeLimit(1000);
        }
        if (question.getMemoryLimit() == null) {
            question.setMemoryLimit(256);
        }
        boolean saved = this.save(question);
        ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "题目创建失败");
        // 发布题目新增事件 → RAG 增量入库（Milvus + ES）
        eventPublisher.publishEvent(new QuestionChangedEvent(Action.ADD, question));
        return question.getId();
    }

    @Override
    public Boolean updateQuestion(QuestionAddDTO dto) {
        Question question = new Question();
        BeanUtils.copyProperties(dto, question);
        boolean updated = this.updateById(question);
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "题目更新失败");
        // updateById 后 question 可能缺少部分字段，重新查完整对象再发事件
        Question full = this.getById(question.getId());
        if (full != null) {
            eventPublisher.publishEvent(new QuestionChangedEvent(Action.UPDATE, full));
        }
        return true;
    }

    @Override
    public Boolean deleteQuestion(Long id) {
        // 删除前先查出完整对象（逻辑删除后 getById 查不到）
        Question question = this.getById(id);
        boolean removed = this.removeById(id);
        ThrowUtils.throwIf(!removed, ErrorCode.OPERATION_ERROR, "题目删除失败");
        if (question != null) {
            // 发布题目删除事件 → RAG 增量删除（Milvus + ES）
            eventPublisher.publishEvent(new QuestionChangedEvent(Action.DELETE, question));
        }
        return true;
    }

    @Override
    public QuestionVO getQuestionVO(Long id) {
        Question question = this.getById(id);
        ThrowUtils.throwIf(question == null, ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        QuestionVO vo = new QuestionVO();
        BeanUtils.copyProperties(question, vo);
        return vo;
    }

    @Override
    public Page<QuestionVO> listQuestionVOByPage(QuestionQueryDTO dto) {
        // TODO: 你来写 —— 练习 MyBatis-Plus 分页 + 标签筛选
        // 提示:
        // 1. QueryWrapper 构建条件:
        //    - title: wrapper.like("title", title)  (注意判空)
        //    - type:  wrapper.eq("type", type)
        //    - difficulty: wrapper.eq("difficulty", difficulty)
        QueryWrapper<Question> queryWrapper = new QueryWrapper<>();
        queryWrapper.like(StringUtils.isNotBlank(dto.getTitle()),"title", dto.getTitle())
                    .eq(StringUtils.isNotBlank(dto.getType()), "type", dto.getType())
                    .eq(StringUtils.isNotBlank(dto.getDifficulty()), "difficulty", dto.getDifficulty());
        // 2. tags 标签筛选(关键!用 apply 拼原生 SQL):
        //    for (String tag : dto.getTags()) {
        //        wrapper.apply("JSON_CONTAINS(tags, {0})", "\"" + tag + "\"");
        //    }
        if (dto.getTags() != null && !dto.getTags().isEmpty()) {
            for (String tag : dto.getTags()) {
            queryWrapper.apply("JSON_CONTAINS(tags, {0})", "\"" + tag + "\"");
        }
    }
        //    注意: JSON_CONTAINS 第二个参数要是 JSON 字符串(带双引号),如 "HashMap"
        // 3. 分页: this.page(new Page<>(current, pageSize), wrapper)
        Page<Question> page = this.page(new Page<>(dto.getCurrent(), dto.getPageSize()),queryWrapper);

        // 4. 转 VO: page.convert(q -> { QuestionVO vo = new QuestionVO(); BeanUtils.copyProperties(q, vo); return vo; })
        Page<QuestionVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        List<QuestionVO> voList = page.getRecords().stream().map(q -> {
            QuestionVO vo = new QuestionVO();
            BeanUtils.copyProperties(q, vo);
            return vo;
        }).collect(Collectors.toList());
        voPage.setRecords(voList);
        return voPage;
    }
}
