package com.seeker.tms.biz.testgen.entities;

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
@TableName("testgen_task")
public class TestGenTaskPO {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String prdName;
    /** 关联文档文件名（换行拼接，仅 UPLOAD 来源）；与主文档一并解析整合 */
    private String relatedNames;
    /** 需求文档展示名：LINK 来源为链接背后真实的文档标题（生成解析后回写），UPLOAD 为文件名 */
    private String prdDisplayName;
    /** 需求来源: UPLOAD 上传 / LINK 文档链接 */
    private String prdSource;
    /** 是否解析文档内图片：true 才解析图片并回填原文，否则仅解析文本 */
    private Boolean parseImage;
    /** 是否解析二级文档（仅 LINK 来源生效）：从主文档中提取引用文档并抓取 */
    private Boolean parseSubDoc;
    private String status;
    private String message;
    private String xmindFileName;
    private String excelFileName;
    private String creator;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
