package com.seeker.tms.biz.confdiff.entities;

import com.seeker.tms.biz.confdiff.enums.AuthType;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 机器出参,敏感字段(password/privateKey/passphrase)脱敏不返回
 */
@Data
public class ConfMachineVO {
    private Integer id;
    private String name;
    private String host;
    private Integer port;
    private String username;
    private AuthType authType;
    private String workDir;
    private String remark;

    @ApiModelProperty("是否已配置密码")
    private Boolean hasPassword;

    @ApiModelProperty("是否已配置私钥")
    private Boolean hasPrivateKey;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
