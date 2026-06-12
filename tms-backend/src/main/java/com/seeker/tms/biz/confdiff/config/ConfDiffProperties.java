package com.seeker.tms.biz.confdiff.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 配置对比相关本地配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "confdiff")
public class ConfDiffProperties {

    /**
     * 本地对比工作区目录(项目内临时目录)。每次对比在其下创建独立 group 目录,
     * 用于存放从远程下载的两个版本配置,对比完成后整组删除。
     * 默认相对路径,解析到应用运行目录下。
     */
    private String workspace = "confdiff-workspace";
}
