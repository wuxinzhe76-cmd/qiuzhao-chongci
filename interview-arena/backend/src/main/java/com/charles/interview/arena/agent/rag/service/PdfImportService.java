package com.charles.interview.arena.agent.rag.service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Service;

import com.charles.interview.arena.agent.rag.model.QuestionEsDoc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PDF 参考答案导入服务
 * <p>
 * 将面试题参考答案 PDF 精细切分后导入 Milvus 向量库 + ES 倒排索引。
 * <p>
 * 切分策略（三级递进）：
 * 1. Q&A 模式检测：识别编号问题（"1."、"2、"、"Q1:"）-> 每个 Q&A 对一个 chunk
 * 2. 段落切分：按双换行分段 -> 每段一个 chunk
 * 3. 滑动窗口：超长段落按 1800 字符窗口 + 200 字符重叠切分
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfImportService {

    private final VectorStore vectorStore;
    private final ElasticsearchOperations elasticsearchOperations;

    @Value("${interview.pdf.source-dir:./参考答案}")
    private String sourceDir;

    private static final int MAX_CHUNK_SIZE = 2000;
    private static final int MIN_CHUNK_SIZE = 50;
    private static final int SLIDING_WINDOW_SIZE = 1800;
    private static final int SLIDING_WINDOW_OVERLAP = 200;
    private static final int EMBEDDING_BATCH_SIZE = 10;

    // ==================== 问题编号模式（用于 Q&A 切分） ====================
    private static final List<Pattern> QUESTION_PATTERNS = Arrays.asList(
            Pattern.compile("^\\d+[\\.、）)\\]]\\s*.+"),           // "1. 问题" / "2、问题"
            Pattern.compile("^Q\\d*\\s*[:：]\\s*.+", Pattern.CASE_INSENSITIVE), // "Q1:" / "Q:"
            Pattern.compile("^[一二三四五六七八九十]+[、\\.）)\\]]\\s*.+"),      // "一、问题"
            Pattern.compile("^问题\\d*\\s*[:：]?\\s*.+"),           // "问题1：" / "问题："
            Pattern.compile("^\\d+\\s*[:：]\\s*.+")                // "1：问题"
    );

    /**
     * 扫描 sourceDir 下所有 PDF 和 .txt 文件，解析 -> 切分 -> 入库 Milvus + ES
     * <p>
     * 对于图片型 PDF（PDFBox 无法提取文本），预先用 OCR 生成同名 .txt 文件，
     * 导入时优先读取 .txt 文件。
     *
     * @return 总入库 chunk 数
     */
    public int importPdfToVectorStore() {
        File sourceDirFile = new File(sourceDir);
        if (!sourceDirFile.exists() || !sourceDirFile.isDirectory()) {
            throw new RuntimeException("PDF 源目录不存在: " + sourceDir);
        }

        // 扫描所有 .pdf 和 .txt 文件
        List<File> allFiles = scanSourceFiles(sourceDirFile);

        // 去重：如果同名 .txt 和 .pdf 都存在，优先用 .txt（OCR 产物），跳过 .pdf
        Set<String> txtBaseNames = new HashSet<>();
        for (File f : allFiles) {
            if (f.getName().toLowerCase().endsWith(".txt")) {
                txtBaseNames.add(f.getName().replace(".txt", ""));
            }
        }
        List<File> sourceFiles = allFiles.stream()
                .filter(f -> {
                    if (f.getName().toLowerCase().endsWith(".pdf")) {
                        String baseName = f.getName().replace(".pdf", "");
                        return !txtBaseNames.contains(baseName); // 有同名 .txt 则跳过 .pdf
                    }
                    return true;
                })
                .toList();

        log.info("扫描到 {} 个源文件（{} 个 .txt + {} 个 .pdf）",
                sourceFiles.size(),
                sourceFiles.stream().filter(f -> f.getName().endsWith(".txt")).count(),
                sourceFiles.stream().filter(f -> f.getName().endsWith(".pdf")).count());

        int totalChunks = 0;
        for (File file : sourceFiles) {
            try {
                int chunks = importSingleFile(file);
                totalChunks += chunks;
                log.info("文件 {} 导入完成，{} 个 chunk", file.getName(), chunks);
            } catch (Exception e) {
                log.error("文件 {} 导入失败: {}", file.getName(), e.getMessage(), e);
            }
        }

        log.info("全部导入完成，共 {} 个 chunk", totalChunks);
        return totalChunks;
    }

    /**
     * 递归扫描目录下所有 .pdf 和 .txt 文件
     */
    private List<File> scanSourceFiles(File dir) {
        List<File> result = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return result;

        for (File file : files) {
            if (file.isDirectory()) {
                result.addAll(scanSourceFiles(file));
            } else {
                String name = file.getName().toLowerCase();
                if (name.endsWith(".pdf") || name.endsWith(".txt")) {
                    result.add(file);
                }
            }
        }
        return result;
    }

    /**
     * 导入单个文件（.pdf 或 .txt）
     */
    private int importSingleFile(File file) throws IOException {
        // 1. 提取全文
        String fullText;
        if (file.getName().toLowerCase().endsWith(".txt")) {
            fullText = cleanText(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } else {
            fullText = extractText(file);
        }

        if (fullText == null || fullText.isBlank()) {
            log.warn("文件 {} 提取文本为空，跳过", file.getName());
            return 0;
        }

        // 2. 推导分类（从目录名或文件名）
        String category = deriveCategory(file);

        // 3. 精细切分
        List<TextChunk> chunks = chunkText(fullText, file.getName(), category);
        log.info("文件 {} 切分为 {} 个 chunk", file.getName(), chunks.size());

        if (chunks.isEmpty()) {
            return 0;
        }

        // 4. 构建 Document 列表（带 metadata）
        List<Document> documents = new ArrayList<>();
        long baseId = System.currentTimeMillis(); // 用时间戳作为基础 ID 避免与 MySQL questionId 冲突

        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            String docId = "src_" + sanitizeFileName(file.getName()) + "_" + i;

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("questionId", baseId + i);     // 合成 Long ID（ES 需要）
            metadata.put("title", chunk.title);
            metadata.put("category", category);
            metadata.put("difficulty", "UNKNOWN");
            metadata.put("source", "pdf");
            metadata.put("sourceFile", file.getName());
            metadata.put("chunkIndex", i);

            String contentString = "题目：" + chunk.title
                    + "\n内容：" + chunk.content
                    + "\n来源：" + file.getName();

            Document doc = new Document(docId, contentString, metadata);
            documents.add(doc);
        }

        // 5. 分批写入 Milvus（DashScope embedding API 一次最多 10 条）
        for (int i = 0; i < documents.size(); i += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(i + EMBEDDING_BATCH_SIZE, documents.size());
            List<Document> batch = documents.subList(i, end);
            vectorStore.add(batch);
            log.info("  Milvus: 已导入 {}/{} chunk", end, documents.size());
        }

        // 6. 写入 ES 倒排索引（BM25 检索用）
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            QuestionEsDoc esDoc = new QuestionEsDoc();
            esDoc.setQuestionId(baseId + i);
            esDoc.setTitle(chunk.title);
            esDoc.setContent(chunk.content);
            esDoc.setAnswer("来源: " + file.getName());
            esDoc.setDifficulty("UNKNOWN");
            esDoc.setCategory(category);
            esDoc.setType("PDF_REFERENCE");
            esDoc.setCreateTime(LocalDateTime.now());
            elasticsearchOperations.save(esDoc);
        }
        log.info("  ES: 已导入 {} 条到倒排索引", chunks.size());

        return chunks.size();
    }

    // ==================== PDF 文本提取 ====================

    /**
     * 使用 Apache PDFBox 提取 PDF 全文
     */
    private String extractText(File pdfFile) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);   // 按阅读顺序排列
            stripper.setAddMoreFormatting(true); // 保留段落结构
            String text = stripper.getText(document);
            return cleanText(text);
        }
    }

    /**
     * 清理 PDF 提取的文本（去除多余空白、修复换行）
     */
    private String cleanText(String raw) {
        return raw
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replaceAll(" +", " ")           // 合并连续空格
                .replaceAll("\n{3,}", "\n\n")    // 最多保留双换行
                .replaceAll(" \n", "\n")         // 去掉行尾空格
                .replaceAll("\n ", "\n")         // 去掉行首空格
                .trim();
    }

    // ==================== 精细切分（三级递进） ====================

    /**
     * 文本切分主入口：三级递进策略
     * <p>
     * Level 1: Q&A 模式检测 -> 每个 Q&A 对一个 chunk
     * Level 2: 段落切分 -> 每段一个 chunk
     * Level 3: 滑动窗口 -> 超长段落按窗口+重叠切分
     */
    private List<TextChunk> chunkText(String text, String fileName, String category) {
        // Level 1: 尝试 Q&A 模式切分
        List<TextChunk> qaChunks = chunkByQAPattern(text, fileName);
        if (!qaChunks.isEmpty()) {
            log.info("  [{}] Q&A 模式切分: {} 个 chunk", fileName, qaChunks.size());
            return qaChunks;
        }

        // Level 2: 段落切分
        List<TextChunk> paragraphChunks = chunkByParagraph(text, fileName);
        log.info("  [{}] 段落切分: {} 个 chunk", fileName, paragraphChunks.size());

        // Level 3: 对超长 chunk 做滑动窗口切分
        List<TextChunk> finalChunks = new ArrayList<>();
        for (TextChunk chunk : paragraphChunks) {
            if (chunk.content.length() > MAX_CHUNK_SIZE) {
                finalChunks.addAll(slidingWindowChunk(chunk, fileName));
            } else {
                finalChunks.add(chunk);
            }
        }

        // 过滤太短的 chunk
        return finalChunks.stream()
                .filter(c -> c.content.length() >= MIN_CHUNK_SIZE)
                .toList();
    }

    /**
     * Level 1: Q&A 模式检测切分
     * <p>
     * 扫描每一行，匹配问题编号模式。如果检测到 >= 2 个问题标记，
     * 则以问题标记为分界点切分，每个 chunk 包含一个完整 Q&A。
     */
    private List<TextChunk> chunkByQAPattern(String text, String fileName) {
        String[] lines = text.split("\n");
        List<Integer> questionLineIndices = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            for (Pattern pattern : QUESTION_PATTERNS) {
                if (pattern.matcher(line).matches()) {
                    questionLineIndices.add(i);
                    break;
                }
            }
        }

        // 至少检测到 2 个问题标记才启用 Q&A 切分
        if (questionLineIndices.size() < 2) {
            return List.of();
        }

        List<TextChunk> chunks = new ArrayList<>();
        for (int i = 0; i < questionLineIndices.size(); i++) {
            int start = questionLineIndices.get(i);
            int end = (i + 1 < questionLineIndices.size())
                    ? questionLineIndices.get(i + 1)
                    : lines.length;

            StringBuilder sb = new StringBuilder();
            String title = "";
            for (int j = start; j < end; j++) {
                String line = lines[j];
                if (j == start) {
                    title = line.trim();
                }
                sb.append(line).append("\n");
            }

            String content = sb.toString().trim();
            if (content.length() >= MIN_CHUNK_SIZE) {
                chunks.add(new TextChunk(title, content));
            }
        }

        return chunks;
    }

    /**
     * Level 2: 段落切分（按双换行分段）
     */
    private List<TextChunk> chunkByParagraph(String text, String fileName) {
        String[] paragraphs = text.split("\n\n");
        List<TextChunk> chunks = new ArrayList<>();

        for (String para : paragraphs) {
            para = para.trim();
            if (para.length() < MIN_CHUNK_SIZE) continue;

            // 提取标题：第一行或前 80 个字符
            String title = extractTitle(para);
            chunks.add(new TextChunk(title, para));
        }

        return chunks;
    }

    /**
     * Level 3: 滑动窗口切分（超长段落）
     * <p>
     * 按 SLIDING_WINDOW_SIZE 字符窗口切分，相邻窗口重叠 SLIDING_WINDOW_OVERLAP 字符。
     */
    private List<TextChunk> slidingWindowChunk(TextChunk longChunk, String fileName) {
        List<TextChunk> result = new ArrayList<>();
        String text = longChunk.content;
        int start = 0;
        int windowIdx = 0;

        while (start < text.length()) {
            int end = Math.min(start + SLIDING_WINDOW_SIZE, text.length());
            String window = text.substring(start, end);

            // 尝试在窗口末尾找句号/换行作为切分点，避免切断句子
            int lastBreak = findLastBreak(window);
            if (lastBreak > MIN_CHUNK_SIZE && end < text.length()) {
                window = text.substring(start, start + lastBreak);
                end = start + lastBreak;
            }

            String title = longChunk.title + " (片段 " + (windowIdx + 1) + ")";
            result.add(new TextChunk(title, window.trim()));

            start = end - SLIDING_WINDOW_OVERLAP;
            if (start <= 0) start = end; // 防止死循环
            windowIdx++;
        }

        return result;
    }

    /**
     * 在窗口内找最后一个自然的断句位置（句号、换行、问号）
     */
    private int findLastBreak(String window) {
        // 优先级：换行 > 句号 > 问号 > 感叹号
        int idx = window.lastIndexOf('\n');
        if (idx > MIN_CHUNK_SIZE) return idx;
        idx = window.lastIndexOf('。');
        if (idx > MIN_CHUNK_SIZE) return idx + 1;
        idx = window.lastIndexOf('？');
        if (idx > MIN_CHUNK_SIZE) return idx + 1;
        idx = window.lastIndexOf('！');
        if (idx > MIN_CHUNK_SIZE) return idx + 1;
        return -1;
    }

    // ==================== 工具方法 ====================

    /**
     * 从段落中提取标题（第一行，最多 80 字符）
     */
    private String extractTitle(String paragraph) {
        int newlineIdx = paragraph.indexOf('\n');
        String firstLine = newlineIdx > 0 ? paragraph.substring(0, newlineIdx) : paragraph;
        if (firstLine.length() > 80) {
            return firstLine.substring(0, 80) + "...";
        }
        return firstLine.trim();
    }

    /**
     * 从文件路径推导分类（用父目录名）
     * <p>
     * 例：参考答案/01-基础面/Attention 升级面.pdf -> "基础面"
     *     参考答案/AI大模型原理和应用面试题速记通关版.pdf -> "综合"
     */
    private String deriveCategory(File pdfFile) {
        File parent = pdfFile.getParentFile();
        if (parent != null) {
            String parentName = parent.getName();
            // 去掉数字前缀：01-基础面 -> 基础面
            String cleaned = parentName.replaceAll("^\\d+-", "");
            if (!cleaned.isEmpty()) {
                return cleaned;
            }
        }
        return "综合";
    }

    /**
     * 文件名转安全字符串（用于 Milvus document ID）
     */
    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[^a-zA-Z0-9一-龥]", "_");
    }

    // ==================== 内部模型 ====================

    private record TextChunk(String title, String content) {}
}
