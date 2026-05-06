package com.seeker.tms.biz.testgen.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.seeker.tms.biz.testgen.entities.XMindNode;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
public class XMindBuilder {

    public static byte[] build(XMindNode root) {
        try {
            String contentJson = buildContentJson(root);
            String metadataJson = buildMetadataJson();
            String manifestJson = buildManifestJson();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                writeEntry(zos, "content.json", contentJson);
                writeEntry(zos, "metadata.json", metadataJson);
                writeEntry(zos, "manifest.json", manifestJson);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            log.error("构建 XMind 文件失败", e);
            throw new RuntimeException("导出 XMind 失败", e);
        }
    }

    private static void writeEntry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String buildContentJson(XMindNode root) {
        JSONArray sheets = new JSONArray();
        JSONObject sheet = new JSONObject();
        sheet.put("id", UUID.randomUUID().toString());
        sheet.put("class", "sheet");
        sheet.put("title", root.getTitle());
        sheet.put("rootTopic", buildTopic(root));
        sheets.add(sheet);
        return sheets.toJSONString();
    }

    private static JSONObject buildTopic(XMindNode node) {
        JSONObject topic = new JSONObject();
        topic.put("id", node.getId() != null ? node.getId() : UUID.randomUUID().toString());
        topic.put("title", node.getTitle());
        topic.put("class", "topic");

        if ("root".equals(node.getType())) {
            topic.put("structureClass", "org.xmind.ui.logic.right");
        }

        if (node.getIcons() != null && !node.getIcons().isEmpty()) {
            JSONArray markers = new JSONArray();
            for (String icon : node.getIcons()) {
                JSONObject marker = new JSONObject();
                marker.put("markerId", icon);
                markers.add(marker);
            }
            topic.put("markers", markers);
        }

        if (node.getChildren() != null && !node.getChildren().isEmpty()) {
            JSONObject children = new JSONObject();
            JSONArray attached = new JSONArray();
            for (XMindNode child : node.getChildren()) {
                attached.add(buildTopic(child));
            }
            children.put("attached", attached);
            topic.put("children", children);
        }

        return topic;
    }

    private static String buildMetadataJson() {
        JSONObject meta = new JSONObject();
        meta.put("creator", new JSONObject() {{ put("name", "tms"); put("version", "1.0"); }});
        return meta.toJSONString();
    }

    private static String buildManifestJson() {
        JSONObject manifest = new JSONObject();
        JSONArray fileEntries = new JSONArray();
        fileEntries.add(new JSONObject() {{ put("full-path", "content.json"); put("media-type", "application/json"); }});
        fileEntries.add(new JSONObject() {{ put("full-path", "metadata.json"); put("media-type", "application/json"); }});
        manifest.put("file-entries", fileEntries);
        return manifest.toJSONString();
    }
}
