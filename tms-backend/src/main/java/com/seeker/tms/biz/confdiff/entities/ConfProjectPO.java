package com.seeker.tms.biz.confdiff.entities;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("config_compare_project")
public class ConfProjectPO {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer machineId;
    private String name;
    private String repoUrl;
    /** 配置文件路径,相对仓库根,逗号分隔 */
    private String configPaths;
    private String defaultBranch;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
