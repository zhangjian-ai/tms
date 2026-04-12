package com.seeker.tms.common.utils;

import com.alibaba.fastjson.JSON;
import okhttp3.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Http {

    public static Response request(String url, String method, HashMap<String, String> params,
                                   HashMap<String, String> headers, HashMap<String, Object> data) throws IOException {

        // 客户端
        OkHttpClient okHttpClient = new OkHttpClient();

        // 查询参数
        if (params != null) {
            url += "?";
            StringBuilder urlBuilder = new StringBuilder(url);
            for (Map.Entry entry : params.entrySet()) {
                urlBuilder.append(entry.getKey()).append("=").append(entry.getValue().toString());
            }
            url = urlBuilder.toString();
        }

        // 请求头
        Headers header = headers != null ? Headers.of(headers) : Headers.of(new HashMap<>());

        // body
        String body = data != null ? JSON.toJSONString(data) : "{}";
        RequestBody requestBody = RequestBody.create(body, MediaType.get("application/json; charset=utf-8"));

        // 请求实例
        Request request = new Request.Builder().url(url).headers(header).method(method.toUpperCase(), requestBody).build();

        // 发起请求
        return okHttpClient.newCall(request).execute();
    }
}
