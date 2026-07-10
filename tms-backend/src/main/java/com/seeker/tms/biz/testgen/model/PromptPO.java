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
@TableName("testgen_prompt")
public class PromptPO {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String name;
    /** 绑定的阶段key,为空表示未标记草稿;非空时全局唯一生效 */
    private String stageKey;
    /** 提示词内容在 MinIO 中的对象键 */
    private String objectKey;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
