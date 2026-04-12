package com.seeker.tms.biz.common.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class CommonControllerTest {

    // 服务地址（确保应用已启动）
    private static final String BASE_URL = "http://localhost:8888";

    @Test
    void contextLoads() {
    }

    /**
     * 测试文件上传接口 - 使用 RestTemplate 发送真实 HTTP 请求
     * 注意：运行此测试前需要先启动应用
     */
    @Test
    void testUploadFile() {
        String filePath = "/Users/seeker/PycharmProjects/Notes/AI/1-MachineLearning/data/ad_data.csv";
        File uploadFile = new File(filePath);

        if (!uploadFile.exists()) {
            System.err.println("文件不存在: " + filePath);
            return;
        }

        RestTemplate restTemplate = new RestTemplate();

        // 构建 multipart 请求体
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(uploadFile));

        // 设置请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        // 请求实例
        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        // 发送 POST 请求
        ResponseEntity<String> response = restTemplate.exchange(
                BASE_URL + "/common/file/upload",
                HttpMethod.POST,
                requestEntity,
                String.class
        );

        // 打印响应
        System.out.println("状态码: " + response.getStatusCode());
        System.out.println("响应内容: " + response.getBody());
    }

    /**
     * 测试获取文件 URL 接口
     */
    @Test
    void testGetFileUrl() {
        RestTemplate restTemplate = new RestTemplate();

        String fileName = "ad_data.csv";
        String url = BASE_URL + "/common/file/url?fileName=" + fileName;

        System.out.println("请求地址: " + url);

        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        System.out.println("响应头：" + response.getHeaders());
        System.out.println("状态码: " + response.getStatusCode());
        System.out.println("响应内容: " + response.getBody());
    }

    /**
     * 测试删除文件接口
     */
    @Test
    void testDeleteFile() {
        RestTemplate restTemplate = new RestTemplate();

        String fileName = "09-ai1.txt";
        String url = BASE_URL + "/common/file/delete?fileName=" + fileName;

        System.out.println("请求地址: " + url);

        ResponseEntity<String> response = restTemplate.postForEntity(url, null, String.class);

        System.out.println("状态码: " + response.getStatusCode());
        System.out.println("响应内容: " + response.getBody());
    }
}