package com.seeker.tms.biz.testgen.service.impl;

import com.seeker.tms.biz.testgen.config.LlmProperties;
import com.seeker.tms.biz.testgen.service.DocumentParserService;
import com.seeker.tms.biz.testgen.utils.PromptLoader;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

@Slf4j
@Service
@AllArgsConstructor
public class DocumentParserServiceImpl implements DocumentParserService {

    private static final int MAX_IMAGE_SIZE = 512 * 1024;
    private static final String IMG_PLACEHOLDER = "___IMAGE_PLACEHOLDER_%d___";
    /** 图片识别并发度：vision API 通常按账号有 RPS 限制，4 是稳妥起点 */
    private static final int IMAGE_RECOGNIZE_CONCURRENCY = 4;

    /** Markdown 图片语法：![alt](src "title")，src 不含空白与右括号，兼容 base64 与 URL */
    private static final java.util.regex.Pattern MD_IMAGE_PATTERN =
            java.util.regex.Pattern.compile("!\\[[^\\]]*]\\(\\s*([^\\s)]+)(?:\\s+\"[^\"]*\")?\\s*\\)");
    /** Markdown 内可能内嵌的 HTML <img src="..."> */
    private static final java.util.regex.Pattern HTML_IMAGE_PATTERN =
            java.util.regex.Pattern.compile("<img\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private final LlmProperties llmProperties;

    @Override
    public String parseDocument(String url, String fileName, boolean parseImage, BiConsumer<Integer, String> progressCallback) {
        if (progressCallback != null) {
            progressCallback.accept(0, "正在下载文档...");
        }

        byte[] fileBytes = downloadFile(url);
        if (fileBytes == null || fileBytes.length == 0) {
            log.error("文档下载失败: {}", url);
            return "";
        }

        if (progressCallback != null) {
            progressCallback.accept(20, "文档下载完成，开始解析...");
        }

        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();

        // 第一步：提取文本。仅在需要解析图片时才抽取图片字节（避免无谓的解码与堆占用）
        List<byte[]> pictures = new ArrayList<>();
        String text = switch (ext) {
            case "pdf" -> parsePdf(fileBytes, pictures, parseImage);
            case "docx" -> parseDocx(fileBytes, pictures, parseImage);
            case "md", "markdown" -> parseMarkdown(fileBytes, pictures, parseImage);
            case "txt" -> new String(fileBytes, StandardCharsets.UTF_8);
            default -> new String(fileBytes, StandardCharsets.UTF_8);
        };

        if (progressCallback != null) {
            progressCallback.accept(40, "文本提取完成，共 " + pictures.size() + " 张图片");
        }

        // 第二步：识别图片并回填占位符。未开启图片解析时，上一步已不抽取图片，pictures 为空，直接跳过
        if (parseImage && !pictures.isEmpty()) {
            text = recognizeAndFillBack(text, pictures, progressCallback);
        }

        if (progressCallback != null) {
            progressCallback.accept(100, "文档解析完成");
        }

        return text;
    }

    // ---- Markdown 解析：图片以内联链接/base64 形式出现，需单独抽取 ----

    /**
     * 解析 Markdown 文本。
     * - parseImage=false：直接清理掉所有图片（Markdown 语法与内嵌 <img>），保持文本纯净；
     * - parseImage=true：把可获取到字节的图片（base64 / http(s) 链接）替换为占位符并收集，
     *   复用 {@link #recognizeAndFillBack} 走与 PDF/DOCX 一致的识别回填逻辑；
     *   无法获取的图片（相对路径等）一律清理。
     */
    private String parseMarkdown(byte[] fileBytes, List<byte[]> pictures, boolean parseImage) {
        String text = new String(fileBytes, StandardCharsets.UTF_8);
        int[] imgIdx = {0};
        text = replaceMarkdownImages(text, MD_IMAGE_PATTERN, pictures, parseImage, imgIdx);
        text = replaceMarkdownImages(text, HTML_IMAGE_PATTERN, pictures, parseImage, imgIdx);
        // 清理图片移除后残留的多余空行
        return text.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    /**
     * 用占位符替换匹配到的图片标记；imgIdx 在多次调用间连续递增以保证占位符与 pictures 下标对齐。
     * 未开启解析、或图片字节不可获取时，替换为空串（即清理）。
     */
    private String replaceMarkdownImages(String text, java.util.regex.Pattern pattern,
                                         List<byte[]> pictures, boolean parseImage, int[] imgIdx) {
        java.util.regex.Matcher m = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String replacement = "";
            if (parseImage) {
                byte[] bytes = loadImageBytes(m.group(1));
                if (bytes != null && bytes.length > 1024) {
                    imgIdx[0]++;
                    pictures.add(bytes);
                    replacement = "\n" + String.format(IMG_PLACEHOLDER, imgIdx[0]) + "\n";
                }
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 从 Markdown 图片 src 加载字节：支持 data:base64 与 http(s) 链接，其余（相对路径/SVG）返回 null */
    private byte[] loadImageBytes(String src) {
        if (src == null) return null;
        src = src.trim();
        try {
            if (src.startsWith("data:")) {
                if (src.regionMatches(true, 0, "data:image/svg", 0, 14)) return null;
                int comma = src.indexOf(',');
                int base64Marker = src.indexOf(";base64");
                if (comma < 0 || base64Marker < 0 || base64Marker > comma) return null;
                // MIME decoder 容忍换行/空白，兼容被折行的 base64
                return Base64.getMimeDecoder().decode(src.substring(comma + 1).trim());
            }
            if (src.startsWith("http://") || src.startsWith("https://")) {
                return downloadFile(src);
            }
        } catch (Exception e) {
            log.warn("Markdown 图片加载失败: {}", src, e);
        }
        return null; // 相对路径等无法获取，忽略
    }

    @Override
    public String recognizeImage(byte[] imageBytes) {
        return recognizeImage(imageBytes, null);
    }

    @Override
    public String recognizeImage(byte[] imageBytes, String surroundingText) {
        // 兼容旧接口，但实际上新逻辑需要完整文档
        // 这里简化处理，直接识别图片内容
        try {
            byte[] compressed = compressImage(imageBytes);

            LlmProperties.ModelConfig visionCfg = llmProperties.getVision();
            OpenAiChatModel visionModel = OpenAiChatModel.builder()
                    .apiKey(visionCfg.getApiKey())
                    .baseUrl(visionCfg.getBaseUrl())
                    .modelName(visionCfg.getModel())
                    .maxTokens(2048)
                    .timeout(Duration.ofSeconds(60))
                    .build();

            String base64 = Base64.getEncoder().encodeToString(compressed);
            String mimeType = detectMimeType(compressed);

            String systemPrompt = "请详细描述这张图片的内容，包括图片中的文字、界面元素、功能说明等。";
            String userPrompt = surroundingText != null ?
                "图片上下文：" + surroundingText + "\n\n请结合上下文描述这张图片。" :
                "请描述这张图片的内容。";

            UserMessage userMsg = UserMessage.from(
                    TextContent.from(userPrompt),
                    ImageContent.from(base64, mimeType)
            );

            Response<AiMessage> response = visionModel.generate(List.of(
                    dev.langchain4j.data.message.SystemMessage.from(systemPrompt),
                    userMsg
            ));
            return response.content().text();
        } catch (Exception e) {
            log.warn("图片识别失败", e);
            return "[图片内容无法识别]";
        }
    }

    // ---- 图片识别并回填占位符 ----
    private String recognizeAndFillBack(String text, List<byte[]> pictures, BiConsumer<Integer, String> progressCallback) {
        // 固定一份原始文本作为所有图片的上下文，避免上一张的识别结果污染下一张
        final String originalText = text;
        final int total = pictures.size();

        // 单次构造 vision model，复用底层连接池
        OpenAiChatModel visionModel = buildVisionModel();
        String systemPrompt = PromptLoader.load("image_recognize_system");

        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(IMAGE_RECOGNIZE_CONCURRENCY, Math.max(1, total)),
                r -> {
                    Thread t = new Thread(r, "img-recognize");
                    t.setDaemon(true);
                    return t;
                });
        AtomicInteger done = new AtomicInteger(0);

        try {
            List<CompletableFuture<int[]>> futures = new ArrayList<>(total);
            String[] descriptions = new String[total];
            for (int i = 0; i < total; i++) {
                final int idx = i;
                String placeholder = String.format(IMG_PLACEHOLDER, i + 1);
                if (!originalText.contains(placeholder)) {
                    descriptions[i] = null;
                    continue;
                }
                futures.add(CompletableFuture.supplyAsync(() -> {
                    String desc = recognizeOneImage(visionModel, systemPrompt,
                            pictures.get(idx), idx + 1, originalText);
                    descriptions[idx] = desc;
                    int finished = done.incrementAndGet();
                    if (progressCallback != null) {
                        int imgProgress = 40 + (int) (finished * 60.0 / total);
                        progressCallback.accept(imgProgress,
                                "已完成 " + finished + "/" + total + " 张图片识别");
                    }
                    return new int[]{idx};
                }, pool));
            }
            // 等全部完成（单张异常已在 recognizeOneImage 内部兜底，不会让整批失败）
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 统一回填——基于原始文本一次性 replace，避免线性 N 次扫描
            String result = originalText;
            for (int i = 0; i < total; i++) {
                if (descriptions[i] == null) continue;
                result = result.replace(String.format(IMG_PLACEHOLDER, i + 1), descriptions[i]);
            }
            return result;
        } finally {
            pool.shutdown();
        }
    }

    private OpenAiChatModel buildVisionModel() {
        LlmProperties.ModelConfig visionCfg = llmProperties.getVision();
        return OpenAiChatModel.builder()
                .apiKey(visionCfg.getApiKey())
                .baseUrl(visionCfg.getBaseUrl())
                .modelName(visionCfg.getModel())
                .maxTokens(2048)
                .timeout(Duration.ofSeconds(180))
                .build();
    }

    private String recognizeOneImage(OpenAiChatModel visionModel, String systemPrompt,
                                     byte[] imageBytes, int index, String fullDoc) {
        try {
            byte[] compressed = compressImage(imageBytes);
            String base64 = Base64.getEncoder().encodeToString(compressed);
            String mimeType = detectMimeType(compressed);

            String userPrompt = PromptLoader.loadWithParams("image_recognize_user",
                    Map.of("index", String.valueOf(index), "doc", fullDoc));

            UserMessage userMsg = UserMessage.from(
                    TextContent.from(userPrompt),
                    ImageContent.from(base64, mimeType)
            );

            Response<AiMessage> response = visionModel.generate(List.of(
                    dev.langchain4j.data.message.SystemMessage.from(systemPrompt),
                    userMsg
            ));
            return response.content().text();
        } catch (Exception e) {
            log.warn("图片识别失败，index={}", index, e);
            return "[图片内容无法识别]";
        }
    }

    // ---- 图片压缩 ----

    private byte[] compressImage(byte[] imageBytes) {
        if (imageBytes.length <= MAX_IMAGE_SIZE) return imageBytes;

        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (original == null) return imageBytes;

            double scale = Math.sqrt((double) MAX_IMAGE_SIZE / imageBytes.length);
            int newWidth = (int) (original.getWidth() * scale);
            int newHeight = (int) (original.getHeight() * scale);

            BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(original, 0, 0, newWidth, newHeight, null);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(resized, "jpeg", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("图片压缩失败，使用原图", e);
            return imageBytes;
        }
    }

    // ---- PDF 解析：文本中留占位符，图片按顺序收集 ----

    private String parsePdf(byte[] fileBytes, List<byte[]> pictures, boolean parseImage) {
        try (PDDocument doc = PDDocument.load(fileBytes)) {
            // 提取全部文本（PDF 转 HTML 再提取，保留图片位置）
            // PDFBox 的 TextStripper 无法标记图片位置，改为逐页处理
            StringBuilder result = new StringBuilder();
            int imgIdx = 0;

            for (int pageNum = 0; pageNum < doc.getNumberOfPages(); pageNum++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(pageNum + 1);
                stripper.setEndPage(pageNum + 1);
                String pageText = stripper.getText(doc);

                // 仅在需要解析图片时才抽取该页图片，否则直接丢弃图片数据、只保留文本
                List<byte[]> pageImages = parseImage
                        ? extractPageImages(doc.getPage(pageNum))
                        : List.of();

                if (pageImages.isEmpty()) {
                    result.append(pageText);
                } else {
                    // 该页有图片：在页面文本中按图片数量插入占位符
                    // 由于 PDFBox 无法精确定位图片在文本中的位置，
                    // 采用策略：将占位符均匀插入到页面文本的段落间
                    List<String> paragraphs = splitParagraphs(pageText);
                    int insertInterval = Math.max(1, paragraphs.size() / (pageImages.size() + 1));

                    int imgInserted = 0;
                    for (int pi = 0; pi < paragraphs.size(); pi++) {
                        result.append(paragraphs.get(pi)).append("\n");
                        if (imgInserted < pageImages.size()
                                && (pi + 1) % insertInterval == 0) {
                            imgIdx++;
                            result.append("\n").append(String.format(IMG_PLACEHOLDER, imgIdx)).append("\n");
                            pictures.add(pageImages.get(imgInserted));
                            imgInserted++;
                        }
                    }
                    // 剩余未插入的图片追加到页面末尾
                    while (imgInserted < pageImages.size()) {
                        imgIdx++;
                        result.append("\n").append(String.format(IMG_PLACEHOLDER, imgIdx)).append("\n");
                        pictures.add(pageImages.get(imgInserted));
                        imgInserted++;
                    }
                }
            }

            return result.toString();
        } catch (Exception e) {
            log.error("PDF 解析失败", e);
            return "";
        }
    }

    private List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.isBlank()) paragraphs.add(line);
        }
        return paragraphs;
    }

    private List<byte[]> extractPageImages(PDPage page) {
        List<byte[]> images = new ArrayList<>();
        try {
            PDResources resources = page.getResources();
            if (resources == null) return images;
            for (var name : resources.getXObjectNames()) {
                var xobj = resources.getXObject(name);
                if (xobj instanceof PDImageXObject imgObj) {
                    BufferedImage bImg = imgObj.getImage();
                    if (bImg.getWidth() < 50 || bImg.getHeight() < 50) continue;
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(bImg, "png", baos);
                    images.add(baos.toByteArray());
                }
            }
        } catch (Exception e) {
            log.warn("提取页面图片失败", e);
        }
        return images;
    }

    // ---- Word (docx) 解析：使用 Mammoth 转 HTML 再转 Markdown ----

    private String parseDocx(byte[] fileBytes, List<byte[]> pictures, boolean parseImage) {
        try {
            // 使用 Mammoth 将 DOCX 转成 HTML
            org.zwobble.mammoth.DocumentConverter converter = new org.zwobble.mammoth.DocumentConverter();
            org.zwobble.mammoth.Result<String> result = converter.convertToHtml(new ByteArrayInputStream(fileBytes));
            String html = result.getValue();

            if (parseImage) {
                // 从 HTML 中提取 base64 编码的图片
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("<img src=\"data:image/([^;]+);base64,([^\"]+)\"");
                java.util.regex.Matcher matcher = pattern.matcher(html);

                while (matcher.find()) {
                    String base64Data = matcher.group(2);
                    try {
                        byte[] imgData = java.util.Base64.getDecoder().decode(base64Data);
                        if (imgData.length > 256 * 1024) {
                            imgData = compressImage(imgData);
                        }
                        if (imgData.length > 1024) {
                            pictures.add(imgData);
                        }
                    } catch (Exception e) {
                        log.warn("解码图片失败: {}", e.getMessage());
                    }
                }

                // 用占位符替换 <img> 标签，供后续识别回填
                int imgIdx = 1;
                while (html.contains("<img")) {
                    html = html.replaceFirst("<img[^>]*>", String.format(IMG_PLACEHOLDER, imgIdx));
                    imgIdx++;
                }
            } else {
                // 不解析图片：直接移除 <img> 标签，不解码 base64、不占用内存
                html = html.replaceAll("<img[^>]*>", "");
            }

            // 使用 flexmark 将 HTML 转成 Markdown（保留标题层级）
            com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter converter2 =
                com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter.builder().build();
            String markdown = converter2.convert(html);

            // 清理多余的空行
            return markdown.replaceAll("\\n{3,}", "\n\n").trim();
        } catch (Exception e) {
            log.error("Word 文档解析失败", e);
            return "";
        }
    }

    // ---- 工具方法 ----

    private byte[] downloadFile(String url) {
        OkHttpClient client = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(30))
                .build();
        Request req = new Request.Builder().url(url).build();
        try (var resp = client.newCall(req).execute()) {
            if (resp.isSuccessful() && resp.body() != null) {
                return resp.body().bytes();
            }
        } catch (Exception e) {
            log.error("下载文件失败: {}", url, e);
        }
        return null;
    }

    private String detectMimeType(byte[] imageBytes) {
        if (imageBytes.length > 3 && imageBytes[0] == (byte) 0x89 && imageBytes[1] == 'P' && imageBytes[2] == 'N') {
            return "image/png";
        }
        return "image/jpeg";
    }
}
