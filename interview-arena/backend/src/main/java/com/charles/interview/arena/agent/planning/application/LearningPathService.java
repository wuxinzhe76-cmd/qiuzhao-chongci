package com.charles.interview.arena.agent.planning.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.charles.interview.arena.agent.memory.semantic.model.WeakPoint;
import com.charles.interview.arena.agent.memory.semantic.UserProfileService;
import com.charles.interview.arena.mapper.QuestionMapper;
import com.charles.interview.arena.model.entity.Question;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 学习路径生成服务（蓝图 §5.7.6）
 * <p>
 * 八股映射：
 * - 基于用户薄弱点（语义记忆），从题库检索相关题目
 * - 调 LLM 生成结构化学习路径（基础 -> 进阶 -> 实战）
 * <p>
 * 触发场景：
 * - 面试结束生成报告时
 * - 用户主动请求「生成学习计划」时
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningPathService {

    private final UserProfileService semanticMemoryService;
    private final QuestionMapper questionMapper;
    private final ChatClient chatClient;

    /** 每个薄弱点取多少道相关题 */
    private static final int QUESTIONS_PER_WEAKNESS = 3;

    private static final String LEARNING_PATH_PROMPT = """
            你是面试学习规划师。基于用户薄弱点生成结构化学习路径。

            【用户薄弱点】
            %s

            【相关题目推荐】
            %s

            【输出要求】
            按「基础巩固 -> 进阶强化 -> 实战演练」三个阶段，输出 Markdown 格式学习路径：
            1. 每个阶段列出具体要学的知识点和要练的题目
            2. 每个阶段预估学习时长（小时）
            3. 每个阶段给一个「达标标准」（如：能独立讲清 HashMap 底层原理）
            """;

    /**
     * 生成用户学习路径
     *
     * @param userId 用户 ID
     * @return Markdown 格式学习路径
     */
    public String generateLearningPath(Long userId) {
        // 1. 取用户薄弱点
        List<WeakPoint> weakPoints = semanticMemoryService.getWeakPoints(userId);
        if (weakPoints == null || weakPoints.isEmpty()) {
            return "暂无薄弱点记录，建议先完成一次模拟面试生成画像。";
        }

        // 2. 每个薄弱点取相关题库题目
        StringBuilder weaknessText = new StringBuilder();
        StringBuilder questionText = new StringBuilder();
        for (WeakPoint w : weakPoints) {
            weaknessText.append(String.format("- %s（掌握度 %d, 出现 %d 次）\n",
                    w.getTopic(), w.getAvgMastery(), w.getExamCount()));

            List<Question> related = searchRelatedQuestions(w.getTopic(), QUESTIONS_PER_WEAKNESS);
            for (Question q : related) {
                questionText.append(String.format("- [%s] %s\n", q.getDifficulty(), q.getTitle()));
            }
        }

        // 3. 调 LLM 生成学习路径
        try {
            String prompt = String.format(LEARNING_PATH_PROMPT,
                    weaknessText.toString(),
                    questionText.toString());
            return chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("学习路径生成失败：userId={}, err={}", userId, e.getMessage());
            return "学习路径生成失败，请稍后重试。薄弱点列表：\n" + weaknessText;
        }
    }

    /**
     * 按关键词检索题库（用于学习路径推荐题目）
     */
    private List<Question> searchRelatedQuestions(String topic, int limit) {
        try {
            LambdaQueryWrapper<Question> wrapper = new LambdaQueryWrapper<Question>()
                    .like(Question::getTitle, topic)
                    .last("LIMIT " + limit);
            List<Question> questions = questionMapper.selectList(wrapper);
            return questions != null ? questions : new ArrayList<>();
        } catch (Exception e) {
            log.warn("检索相关题目失败：topic={}, err={}", topic, e.getMessage());
            return new ArrayList<>();
        }
    }
}
