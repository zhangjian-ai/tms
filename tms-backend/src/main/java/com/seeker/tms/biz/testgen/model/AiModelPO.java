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
@TableName("ai_model")
public class AiModelPO {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String name;
    private String baseUrl;
    private String apiKey;
    private String modelName;
    private Boolean useAsVision;
    private Boolean useAsThinking;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
