package com.seeker.tms.biz.device.entities;

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
@TableName("device_connection")
public class DeviceConnectionPO {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer deviceId;
    private String adbHost;
    private String adbPort;
    private String proxyHost;
    private String proxyPort;
    private String connection;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
