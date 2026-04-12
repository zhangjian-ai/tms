package com.seeker.tms.biz.testmodule.entities;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.seeker.tms.common.enums.BoolStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("module")
public class ModulePO {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String name;
    private Integer parentId;
    private BoolStatus isProduct = BoolStatus.FALSE;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
