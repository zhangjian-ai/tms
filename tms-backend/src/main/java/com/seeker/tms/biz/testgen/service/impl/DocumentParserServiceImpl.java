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
import java.util.function.BiConsumer;

@Slf4j
@Service
@AllArgsConstructor
public class DocumentParserServiceImpl implements DocumentParserService {

    private static final int MAX_IMAGE_SIZE = 512 * 1024;
    private static final String IMG_PLACEHOLDER = "___IMAGE_PLACEHOLDER_%d___";

    private final LlmProperties llmProperties;

    @Override
    public String parseDocument(String url, String fileName, BiConsumer<Integer, String> progressCallback) {
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

        // 第一步：提取文本（图片位置留占位符）+ 收集图片
        List<byte[]> pictures = new ArrayList<>();
        String text = switch (ext) {
            case "pdf" -> parsePdf(fileBytes, pictures);
            case "docx" -> parseDocx(fileBytes, pictures);
            case "txt", "md" -> new String(fileBytes, StandardCharsets.UTF_8);
            default -> new String(fileBytes, StandardCharsets.UTF_8);
        };

        if (progressCallback != null) {
            progressCallback.accept(40, "文本提取完成，共 " + pictures.size() + " 张图片");
        }

        // 第二步：识别图片并回填到占位符位置
        if (!pictures.isEmpty()) {
            text = recognizeAndFillBack(text, pictures, progressCallback);
        }

        if (progressCallback != null) {
            progressCallback.accept(100, "文档解析完成");
        }

        return text;
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
        for (int i = 0; i < pictures.size(); i++) {
            String placeholder = String.format(IMG_PLACEHOLDER, i + 1);
            if (!text.contains(placeholder)) continue;

            if (progressCallback != null) {
                int imgProgress = 40 + (int) ((i + 1) * 60.0 / pictures.size());
                progressCallback.accept(imgProgress, "正在识别第 " + (i + 1) + "/" + pictures.size() + " 张图片...");
            }

            // 传入完整文档和图片序号，让模型自己理解上下文
            String desc = recognizeImageWithFullDoc(pictures.get(i), i + 1, text);
            text = text.replace(placeholder, desc);
        }
        return text;
    }

    private String recognizeImageWithFullDoc(byte[] imageBytes, int index, String fullDoc) {
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

            String systemPrompt = PromptLoader.load("image_recognize_system");
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
            log.warn("图片识别失败", e);
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

    private String parsePdf(byte[] fileBytes, List<byte[]> pictures) {
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

                // 提取该页图片
                PDPage page = doc.getPage(pageNum);
                List<byte[]> pageImages = extractPageImages(page);

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

    private String parseDocx(byte[] fileBytes, List<byte[]> pictures) {
        try {
            // 使用 Mammoth 将 DOCX 转成 HTML
            org.zwobble.mammoth.DocumentConverter converter = new org.zwobble.mammoth.DocumentConverter();
            org.zwobble.mammoth.Result<String> result = converter.convertToHtml(new ByteArrayInputStream(fileBytes));
            String html = result.getValue();

            // 从 HTML 中提取 base64 编码的图片
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("<img src=\"data:image/([^;]+);base64,([^\"]+)\"");
            java.util.regex.Matcher matcher = pattern.matcher(html);

            int imgIdx = 1;
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
                imgIdx++;
            }

            // 用占位符替换 <img> 标签
            imgIdx = 1;
            while (html.contains("<img")) {
                html = html.replaceFirst("<img[^>]*>", String.format(IMG_PLACEHOLDER, imgIdx));
                imgIdx++;
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
