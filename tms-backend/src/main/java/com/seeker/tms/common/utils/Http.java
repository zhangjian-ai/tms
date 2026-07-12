package com.seeker.tms.common.utils;

import com.alibaba.fastjson.JSON;
import okhttp3.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Http {

    public static Response request(String url, String method, HashMap<String, String> params,
                                   HashMap<String, String> headers, HashMap<String, Object> data) throws IOException {

        OkHttpClient okHttpClient = new OkHttpClient();

        if (params != null) {
            url += "?";
            StringBuilder urlBuilder = new StringBuilder(url);
            for (Map.Entry entry : params.entrySet()) {
                urlBuilder.append(entry.getKey()).append("=").append(entry.getValue().toString());
            }
            url = urlBuilder.toString();
        }

        Headers header = headers != null ? Headers.of(headers) : Headers.of(new HashMap<>());

        String body = data != null ? JSON.toJSONString(data) : "{}";
        RequestBody requestBody = RequestBody.create(body, MediaType.get("application/json; charset=utf-8"));

        Request request = new Request.Builder().url(url).headers(header).method(method.toUpperCase(), requestBody).build();

        return okHttpClient.newCall(request).execute();
    }
}
