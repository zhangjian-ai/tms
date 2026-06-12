package com.seeker.tms.biz.confdiff.entities;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.seeker.tms.biz.confdiff.enums.AuthType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("config_compare_machine")
public class ConfMachinePO {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String name;
    private String host;
    private Integer port;
    private String username;
    private AuthType authType;
    private String password;
    private String privateKey;
    private String passphrase;
    private String workDir;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
