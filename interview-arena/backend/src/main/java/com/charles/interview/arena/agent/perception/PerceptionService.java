package com.charles.interview.arena.agent.perception;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.charles.interview.arena.agent.guardrail.input.InputSanitizer;
import com.charles.interview.arena.agent.perception.intent.EntityExtractor;
import com.charles.interview.arena.agent.perception.intent.IntentClassifier;
import com.charles.interview.arena.agent.perception.model.Intent;
import com.charles.interview.arena.agent.perception.model.Observation;
import com.charles.interview.arena.agent.perception.model.PerceptionResult;
import com.charles.interview.arena.agent.perception.model.RiskAssessment;
import com.charles.interview.arena.agent.perception.model.RawInput;
import com.charles.interview.arena.agent.perception.model.TrustLevel;
import com.charles.interview.arena.agent.perception.normalization.ObservationNormalizer;
import com.charles.interview.arena.agent.perception.normalization.TextNormalizer;
import com.charles.interview.arena.agent.perception.parsing.ImageContentParser;
import com.charles.interview.arena.agent.perception.parsing.PdfContentParser;
import com.charles.interview.arena.agent.perception.validation.FileValidator;
import com.charles.interview.arena.agent.perception.validation.InputFormatValidator;
import com.charles.interview.arena.agent.perception.validation.ResourceLimitValidator;

/**
 * 感知服务(7 步管线主线)
 * <p>
 * 职责:将外部输入转换为 Agent 内部能识别、校验、继续处理的标准数据。
 * 输出 PerceptionResult,为后续编排层提供可靠输入。
 * <p>
 * 处理管线:
 * 1. 输入格式校验(非空/长度/字段)
 * 2. 文件校验(MIME/大小)
 * 3. 资源限制校验(Token 预估)
 * 4. 多模态解析(PDF/图片/文本)
 * 5. 文本规范化(Unicode/不可见字符)
 * 6. 安全检查(注入检测/信任标记)
 * 7. 意图分类 + 实体提取
 * <p>
 * 不负责:完整 Context 组装(那是 ContextAssembler)
 *        工具返回感知(那是 ToolResultParser,在 ReAct 循环内)
 */
@Service
public class PerceptionService {

    private static final Logger log = LoggerFactory.getLogger(PerceptionService.class);

    private final InputFormatValidator formatValidator;
    private final FileValidator fileValidator;
    private final ResourceLimitValidator resourceLimitValidator;
    private final PdfContentParser pdfContentParser;
    private final ImageContentParser imageContentParser;
    private final TextNormalizer textNormalizer;
    private final ObservationNormalizer observationNormalizer;
    private final InputSanitizer inputSanitizer;
    private final IntentClassifier intentClassifier;
    private final EntityExtractor entityExtractor;

    public PerceptionService(
            InputFormatValidator formatValidator,
            FileValidator fileValidator,
            ResourceLimitValidator resourceLimitValidator,
            PdfContentParser pdfContentParser,
            ImageContentParser imageContentParser,
            TextNormalizer textNormalizer,
            ObservationNormalizer observationNormalizer,
            InputSanitizer inputSanitizer,
            IntentClassifier intentClassifier,
            EntityExtractor entityExtractor) {
        this.formatValidator = formatValidator;
        this.fileValidator = fileValidator;
        this.resourceLimitValidator = resourceLimitValidator;
        this.pdfContentParser = pdfContentParser;
        this.imageContentParser = imageContentParser;
        this.textNormalizer = textNormalizer;
        this.observationNormalizer = observationNormalizer;
        this.inputSanitizer = inputSanitizer;
        this.intentClassifier = intentClassifier;
        this.entityExtractor = entityExtractor;
    }

    /**
     * 感知处理(7 步管线)
     *
     * @param input 原始输入
     * @return 感知结果
     */
    public PerceptionResult perceive(RawInput input) {
        log.info("感知处理开始: inputType={}", input != null ? input.inputType() : "null");

        try {
            // 1. 输入格式校验
            formatValidator.validate(input);

            // 2. 文件校验(如果有文件)
            fileValidator.validate(input);

            // 3. 资源限制校验
            resourceLimitValidator.check(input.text());

            // 4. 多模态解析(根据输入类型)
            List<Observation> observations = parseInput(input);

            // 5. 文本规范化(已在解析中完成,这里做最终检查)
            if (textNormalizer.hasAbnormalEncoding(input.text())) {
                log.warn("检测到异常编码,已清理");
            }

            // 6. 安全检查(注入检测 + 信任标记)
            RiskAssessment riskAssessment = checkSecurity(input.text());
            if ("BLOCKED".equals(riskAssessment.level())) {
                log.warn("输入被安全检查拦截: {}", riskAssessment.reason());
                return PerceptionResult.blocked(riskAssessment.reason());
            }

            // 7. 意图分类 + 实体提取
            String sanitizedText = inputSanitizer.sanitizeInput(input.text());
            Intent intent = intentClassifier.classify(sanitizedText);
            Map<String, Object> entities = entityExtractor.extract(sanitizedText, intent);

            log.info("感知处理完成: intent={}, observations={}, risk={}",
                    intent, observations.size(), riskAssessment.level());

            return new PerceptionResult(
                    observations,
                    intent,
                    entities,
                    riskAssessment,
                    TrustLevel.UNTRUSTED
            );

        } catch (IllegalArgumentException e) {
            log.warn("输入校验失败: {}", e.getMessage());
            return PerceptionResult.blocked("输入校验失败: " + e.getMessage());
        } catch (IllegalStateException e) {
            log.warn("资源限制: {}", e.getMessage());
            return PerceptionResult.blocked("资源限制: " + e.getMessage());
        } catch (Exception e) {
            log.error("感知处理异常", e);
            return PerceptionResult.blocked("感知处理异常: " + e.getMessage());
        }
    }

    /**
     * 轻量感知(仅文本,用于简单场景)
     */
    public PerceptionResult perceiveText(String text) {
        RawInput input = new RawInput(
                text, null, null, null, null, null, RawInput.InputType.TEXT);
        return perceive(input);
    }

    /**
     * 多模态解析(根据输入类型选择解析器)
     */
    private List<Observation> parseInput(RawInput input) {
        List<Observation> observations = new ArrayList<>();

        switch (input.inputType()) {
            case PDF -> {
                Observation pdfObs = pdfContentParser.parse(input.fileData());
                observations.add(pdfObs);
            }
            case IMAGE -> {
                Observation imgObs = imageContentParser.parse(input.fileData(), input.mimeType());
                observations.add(imgObs);
            }
            case TOOL_RESULT -> {
                // 工具返回不经过 PerceptionService,这里不处理
                observations.add(Observation.userInput(input.text()));
            }
            case TEXT -> {
                Observation textObs = observationNormalizer.normalizeUserText(input.text());
                observations.add(textObs);
            }
        }

        return observations;
    }

    /**
     * 安全检查(注入检测 + 风险评估)
     */
    private RiskAssessment checkSecurity(String text) {
        if (inputSanitizer.detectInjection(text)) {
            return new RiskAssessment("BLOCKED", "检测到 Prompt 注入攻击");
        }

        if (textNormalizer.hasAbnormalEncoding(text)) {
            return new RiskAssessment("MEDIUM", "检测到异常编码(已清理)");
        }

        return new RiskAssessment("LOW", "通过安全检查");
    }
}
