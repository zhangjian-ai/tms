package com.seeker.tms.biz.common.controller;

import com.seeker.tms.common.utils.MinioUtil;
import com.seeker.tms.common.utils.Result;
import io.swagger.annotations.Api;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Api(tags = "通用接口")
@RequestMapping("/common")
@RestController
@RequiredArgsConstructor
public class CommonController {

    private final MinioUtil minioUtil;

    @PostMapping("/file/upload")
    public Result<Map<String, String>> uploadFile(@RequestParam("file") MultipartFile file){
        Map<String, String> info = new HashMap<>();

        boolean isSuccess = minioUtil.uploadFile(file);
        if (isSuccess){
            info.put("msg", "success");

            String url = minioUtil.getUrl(file.getOriginalFilename());
            info.put("url", url);
            return Result.success(info);
        }else {
            info.put("msg", "failure");
            return Result.fail(info);
        }
    }

    @GetMapping("/file/url")
    public Result<String> getUrl(@RequestParam("fileName") String fileName){
        String url = minioUtil.getUrl(fileName);
        return Result.success(url);
    }

    @PostMapping("/file/delete")
    public Result<?> deleteFile(@RequestParam("fileName") String fileName){
        log.info("删除文件: " + fileName);
        minioUtil.deleteFile(fileName);
        return Result.success();
    }
}
