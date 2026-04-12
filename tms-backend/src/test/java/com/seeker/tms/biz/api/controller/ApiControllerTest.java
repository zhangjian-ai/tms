package com.seeker.tms.biz.api.controller;

import com.seeker.tms.common.utils.Http;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;

class ApiControllerTest {

    private static String url = "http://127.0.0.1:8888";
    private static String method = "post";


    @Test
    void addApi() throws IOException {

        HashMap<String, Object> data = new HashMap<>();
        data.put("name", "httpbin-get");
        data.put("url", "www.httpbin.org/get");
        data.put("proto", "http");
        data.put("method", "get");

        Response resp = Http.request(url + "/api/add", method, null, null, data);

        System.out.println(resp.body().string());

    }

    @Test
    void invokeApi() throws IOException {

        HashMap<String, Object> invoke = new HashMap<>();

        HashMap<String, Object> data = new HashMap<>();
        data.put("name", "xiaosan");
        data.put("age", 28);

        //
        invoke.put("apiId", 1);
        invoke.put("data", data);

        Response resp = Http.request(url + "/api/invoke", method, null, null, invoke);

        System.out.println(resp.body().string());

    }
}