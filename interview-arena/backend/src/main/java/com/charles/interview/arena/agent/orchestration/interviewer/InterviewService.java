package com.charles.interview.arena.agent.orchestration.interviewer;

import com.baomidou.mybatisplus.extension.service.IService;
import com.charles.interview.arena.model.dto.interview.InterviewAnswerDTO;
import com.charles.interview.arena.model.dto.interview.InterviewStartDTO;
import com.charles.interview.arena.model.entity.InterviewSession;
import com.charles.interview.arena.model.vo.InterviewAnswerVO;
import com.charles.interview.arena.model.vo.InterviewStartVO;

/**
 * AI 面试核心服务（蓝图 §5.4）
 * <p>
 * 三大核心方法：
 * <ul>
 *   <li>startInterview: 创建 session + 抽第一道题 + AI 生成开场提问</li>
 *   <li>answerInterview: 即时落库 + Redis 推进 + AI 评估 + 三层控制路由</li>
 *   <li>endInterview: session.status -> 1 + 删 Redis + 发 MQ</li>
 * </ul>
 */
public interface InterviewService extends IService<InterviewSession> {

    /**
     * 开始面试
     */
    InterviewStartVO startInterview(InterviewStartDTO dto, Long userId);

    /**
     * 提交回答（循环调用）
     */
    InterviewAnswerVO answerInterview(InterviewAnswerDTO dto, Long userId);

    /**
     * 结束面试（用户主动 / 自动触发）
     */
    Boolean endInterview(Long sessionId, Long userId);
}
