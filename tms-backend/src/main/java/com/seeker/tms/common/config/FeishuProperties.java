package com.seeker.tms.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 飞书开放平台配置。凭据配置在 application-local.yml 的 feishu 段。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "feishu")
public class FeishuProperties {

    /** 飞书自建应用 App ID */
    private String appId;

    /** 飞书自建应用 App Secret */
    private String appSecret;

    /** OAuth 重定向地址（指向前端回调页，如 https://xxx/auth/feishu/callback） */
    private String redirectUri;

    /** 开放平台 API 基地址 */
    private String baseUrl = "https://open.feishu.cn";

    /**
     * 授权范围（空格分隔的权限码），决定 user_access_token 能访问的资源。
     * 必须与飞书后台「已开通且已发布」的用户身份权限一致，否则授权会报无效 scope。
     * 默认涵盖 docx / wiki / 电子表格 / 云文档媒体的只读范围。
     */
    private String scope = "docx:document:readonly "
            + "wiki:wiki:readonly wiki:node:read "
            + "sheets:spreadsheet:readonly "
            + "drive:file:download";
}
