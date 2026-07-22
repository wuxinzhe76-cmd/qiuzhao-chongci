package com.charles.interview.arena.agent.perception.parsing;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.perception.model.Observation;
import com.charles.interview.arena.agent.perception.normalization.ObservationNormalizer;

/**
 * PDF 内容解析器(Apache PDFBox)
 * <p>
 * 职责:从 PDF 中提取结构化文本 + 页码 + 表格信息。
 * 保留结构不扁平化,为多模态 LLM 提供上下文。
 */
@Component
public class PdfContentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfContentParser.class);

    private final ObservationNormalizer observationNormalizer;

    public PdfContentParser(ObservationNormalizer observationNormalizer) {
        this.observationNormalizer = observationNormalizer;
    }

    /**
     * 解析 PDF 文件
     *
     * @param pdfData PDF 文件字节数组
     * @return 包含文本和结构化元数据的 Observation
     */
    public Observation parse(byte[] pdfData) {
        if (pdfData == null || pdfData.length == 0) {
            return Observation.toolError("PDF 数据为空");
        }

        try (var document = org.apache.pdfbox.Loader.loadPDF(pdfData)) {

            int pageCount = document.getNumberOfPages();
            var extractor = new org.apache.pdfbox.text.PDFTextStripper();
            extractor.setSortByPosition(true);

            var textBuilder = new StringBuilder();
            int tableCount = 0;

            for (int i = 1; i <= pageCount; i++) {
                extractor.setStartPage(i);
                extractor.setEndPage(i);
                String pageText = extractor.getText(document);

                textBuilder.append("--- 第 ").append(i).append(" 页 ---\n");
                textBuilder.append(pageText).append("\n");

                // 简单表格检测(含多个连续 tab 或 | 的行视为表格)
                if (pageText.lines().anyMatch(line ->
                        line.chars().filter(c -> c == '\t' || c == '|').count() > 3)) {
                    tableCount++;
                }
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("pages", pageCount);
            metadata.put("tables", tableCount);
            metadata.put("fileSize", pdfData.length);

            log.info("PDF 解析完成: pages={}, tables={}, textLength={}",
                    pageCount, tableCount, textBuilder.length());

            return observationNormalizer.normalizePdfContent(textBuilder.toString(), metadata);

        } catch (Exception e) {
            log.error("PDF 解析失败: {}", e.getMessage());
            return Observation.toolError("PDF 解析失败: " + e.getMessage());
        }
    }
}
