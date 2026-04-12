package com.seeker.tms.biz.perftest.controller;

import com.alibaba.fastjson.JSON;
import okhttp3.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PerfTestControllerTest {

    @Test
    public void testAddTest() throws IOException {

        String url = "http://127.0.0.1:8888/perf/addTest";
        OkHttpClient okHttpClient = new OkHttpClient();

        // 请求数据构造
        HashMap<String, Object> map = new HashMap<>();
        map.put("name", "调试任务1");
        ArrayList<Integer> apiIds = new ArrayList<>();
        apiIds.add(1);
        apiIds.add(2);
        map.put("apiIds", apiIds);

        // 构建 apiInvokes 列表 - 必须是 List 对象，不能是字符串
        List<HashMap<String, Object>> apiInvokes = new ArrayList<>();

        // 第一个 API 调用配置
        HashMap<String, Object> apiInvoke1 = new HashMap<>();
        // headers 列表
        List<HashMap<String, String>> headers1 = new ArrayList<>();
        HashMap<String, String> header1 = new HashMap<>();
        header1.put("key", "Content-Type");
        header1.put("value", "application/json");
        headers1.add(header1);
        apiInvoke1.put("headers", headers1);
        apiInvoke1.put("params", new ArrayList<>());
        apiInvoke1.put("data", new ArrayList<>());
        apiInvoke1.put("verify", new ArrayList<>());
        apiInvokes.add(apiInvoke1);

        // 第二个 API 调用配置（对应第二个 apiId）
        HashMap<String, Object> apiInvoke2 = new HashMap<>();
        apiInvoke2.put("headers", new ArrayList<>());
        apiInvoke2.put("params", new ArrayList<>());
        apiInvoke2.put("data", new ArrayList<>());
        apiInvoke2.put("verify", new ArrayList<>());
        apiInvokes.add(apiInvoke2);

        map.put("apiInvokes", apiInvokes);

        // 请求体
        RequestBody requestBody = RequestBody.create(JSON.toJSONString(map), MediaType.get("application/json; charset=utf-8"));

        // 请求
        Request request = new Request.Builder().url(url).post(requestBody).build();

        // 发起请求
        Response response = okHttpClient.newCall(request).execute();

        System.out.println(response.body().string());

    }

}