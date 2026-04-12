package com.seeker.tms.biz.device.entities;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.seeker.tms.common.enums.BoolStatus;
import com.seeker.tms.common.enums.DeviceSys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("device")
public class DevicePO {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String name;
    private String serial;
    private String brand;
    private String model;
    private DeviceSys deviceSys;
    private String osVersion;
    private Short width;
    private Short height;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
