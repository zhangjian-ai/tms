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
            log.error("文件上传失败", e);
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
            log.error("文件上传失败: {}", objectKey, e);
            throw new RuntimeException("文件上传失败: " + e.getMessage());
        }
    }

    /**
     * 获取文件临时访问链接，链接默认有效时间300s
     */
    public String getUrl(String fileName) {
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

            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(bucketName)
                    .object(fileName)
                    .method(Method.GET)
                    .extraQueryParams(queryParams)
                    .expiry(minioConfig.getExpire()).build());
        } catch (Exception e) {
            throw new RuntimeException("获取下载链接失败: " + e.getMessage());
        }
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
            log.error("文件删除失败", e);
            throw new RuntimeException("文件删除失败: " + e.getMessage());
        }
    }
}
