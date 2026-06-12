package com.seeker.tms.biz.confdiff.entities;

import com.seeker.tms.biz.confdiff.enums.AuthType;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class ConfMachineDTO {

    @ApiModelProperty("机器ID,新增不传,编辑必传")
    private Integer id;

    @NotBlank(message = "机器名称不能为空")
    @ApiModelProperty("机器名称")
    private String name;

    @NotBlank(message = "主机地址不能为空")
    @ApiModelProperty("SSH主机地址")
    private String host;

    @ApiModelProperty("SSH端口,默认22")
    private Integer port = 22;

    @NotBlank(message = "用户名不能为空")
    @ApiModelProperty("SSH用户名")
    private String username;

    @NotNull(message = "鉴权方式不能为空")
    @ApiModelProperty("鉴权方式:password/private_key")
    private AuthType authType;

    @ApiModelProperty("登录密码(password鉴权时使用)")
    private String password;

    @ApiModelProperty("私钥PEM内容(private_key鉴权时使用)")
    private String privateKey;

    @ApiModelProperty("私钥口令(可空)")
    private String passphrase;

    @NotBlank(message = "工作目录不能为空")
    @ApiModelProperty("远程统一工作目录")
    private String workDir;

    @ApiModelProperty("备注")
    private String remark;
}
