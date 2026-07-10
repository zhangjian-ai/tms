package com.seeker.tms.biz.testgen.model;

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
@TableName("testgen_stage")
public class PromptStagePO {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String stageKey;
    private String stageName;
    private String description;
    private Integer sortNo;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
