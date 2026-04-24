package com.seeker.tms.common.utils;

import lombok.AllArgsConstructor;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;


@AllArgsConstructor
class MinioUtilTest {

    @Resource
    private final MinioUtil minioUtil;
//    private static String url = "http://127.0.0.1:8888";
//    private static String method = "post";


    @Test
    void addApi() throws IOException {
        minioUtil.deleteFile("装修系统优化需求v2（含v1未完成内容）.docx.target.txt");
    }
}