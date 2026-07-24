package com.seeker.tms.common.utils;

import com.seeker.tms.common.config.MinioConfig;
import io.minio.*;
import io.minio.http.Method;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;


@Slf4j
@RequiredArgsConstructor
@Component
@Data
public class MinioUtil {

    private final MinioConfig minioConfig;
    private final MinioClient minioClient;

    /**
     * 保存文件
     */
    public boolean uploadFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new RuntimeException("上传文件不能为空");
        }

        try {
            // 检查存储同是否存在，不存在则创建
            String bucketName = minioConfig.getBucketName();
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("创建存储桶: " + bucketName);
            }

            // 上传文件，同名文件直接覆盖
            InputStream inputStream = file.getInputStream();
            ObjectWriteResponse objectWriteResponse = minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(file.getOriginalFilename())
                            .stream(inputStream, file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build());

            // 使用 etag 判断上传是否成功（etag 在上传成功后一定存在）
            return objectWriteResponse.etag() != null;

        } catch (Exception e) {
            log.error("文件上传失败: {}", e.toString());
            throw new RuntimeException("文件上传失败: " + e.getMessage());
        }
    }

    /**
     * 按 objectKey + 字节数组上传文件
     */
    public boolean uploadFile(String objectKey, byte[] data) {
        return uploadFile(objectKey, data, "application/octet-stream");
    }

    /**
     * 按 objectKey + 字节数组 + 内容类型上传文件
     */
    public boolean uploadFile(String objectKey, byte[] data, String contentType) {
        try {
            String bucketName = minioConfig.getBucketName();
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }

            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(data);
            ObjectWriteResponse resp = minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .stream(bais, data.length, -1)
                            .contentType(contentType)
                            .build());
            return resp.etag() != null;
        } catch (Exception e) {
            log.error("文件上传失败: {}: {}", objectKey, e.toString());
            throw new RuntimeException("文件上传失败: " + e.getMessage());
        }
    }

    /**
     * 读取对象内容为字符串(UTF-8)。对象不存在或读取失败时抛异常。
     */
    public String getContent(String objectKey) {
        String bucketName = minioConfig.getBucketName();
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucketName)
                .object(objectKey)
                .build())) {
            byte[] data = in.readAllBytes();
            return new String(data, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("读取对象内容失败: " + objectKey + ", " + e.getMessage(), e);
        }
    }

    /**
     * 获取文件临时访问链接（对外）：经 toPublicUrl 改写为 https 前缀，供前端下载/预览。
     */
    public String getUrl(String fileName) {
        return toPublicUrl(presignGet(fileName));
    }

    /**
     * 获取文件临时访问链接（服务端内部）：直连 endpoint，不做 https 改写，供后端 OkHttp 拉取解析。
     */
    public String getInternalUrl(String fileName) {
        return presignGet(fileName);
    }

    /** 生成 GET 预签名 URL（先 statObject 校验存在）；不做任何对外改写。 */
    private String presignGet(String fileName) {
        String bucketName = minioConfig.getBucketName();
        try {
            // 检查文件状态，不存在时会抛出异常
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(fileName).build());

            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(bucketName)
                    .object(fileName)
                    .method(Method.GET)
                    .expiry(minioConfig.getExpire()).build());

        } catch (Exception e) {
            throw new RuntimeException("获取文件链接失败: " + e.getMessage());
        }
    }

    /**
     * 获取强制下载(另存为)的临时链接:通过 response-content-disposition 让浏览器下载而非内联打开
     */
    public String getDownloadUrl(String fileName, String downloadName) {
        String bucketName = minioConfig.getBucketName();
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(fileName).build());

            java.util.Map<String, String> queryParams = new java.util.HashMap<>();
            queryParams.put("response-content-disposition", "attachment; filename=\"" + downloadName + "\"");

            return toPublicUrl(minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(bucketName)
                    .object(fileName)
                    .method(Method.GET)
                    .extraQueryParams(queryParams)
                    .expiry(minioConfig.getExpire()).build()));
        } catch (Exception e) {
            throw new RuntimeException("获取下载链接失败: " + e.getMessage());
        }
    }

    /**
     * 按 minio.public-path-prefix 把预签名 URL 改写为 https://<endpoint-host><prefix>/...；前缀为空则原样返回。
     * 仅换 scheme/host 并套上前缀，保留 path 与查询参数，不影响 S3 签名。
     */
    private String toPublicUrl(String presignedUrl) {
        String prefix = minioConfig.getPublicPathPrefix();
        String endpoint = minioConfig.getEndpoint();
        if (prefix == null || prefix.isBlank()
                || endpoint == null || presignedUrl == null
                || !presignedUrl.startsWith(endpoint)) {
            return presignedUrl;
        }
        // 规范化前缀：以 / 开头、不以 / 结尾
        String p = prefix.startsWith("/") ? prefix : "/" + prefix;
        if (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        String host = java.net.URI.create(endpoint).getHost();
        // endpoint 之后即 /bucket/object?query，套上前缀、强制 https、去掉端口
        return "https://" + host + p + presignedUrl.substring(endpoint.length());
    }

    /**
     * 删除文件
     */
    public void deleteFile(String fileName) {
        String bucketName = minioConfig.getBucketName();
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .object(fileName)
                    .build());
        } catch (Exception e) {
            log.error("文件删除失败: {}", e.toString());
            throw new RuntimeException("文件删除失败: " + e.getMessage());
        }
    }
}