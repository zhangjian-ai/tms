package com.seeker.tms.biz.device.controller;

import com.seeker.tms.biz.device.entities.*;
import com.seeker.tms.biz.device.service.DeviceConnectionService;
import com.seeker.tms.biz.device.service.DeviceService;
import com.seeker.tms.common.entities.PageResult;
import com.seeker.tms.common.utils.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@Api(tags = "设备管理")
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/device")
public class DeviceController {

    private final DeviceService deviceService;

    private final DeviceConnectionService deviceConnectionService;

    @ApiOperation("设备列表")
    @GetMapping("/list")
    public Result<PageResult<DeviceVO>> list(DeviceQueryDTO deviceQueryDTO){
        PageResult<DeviceVO> pageResult = deviceService.deviceList(deviceQueryDTO);
        return Result.success(pageResult);
    }

    @ApiOperation("根据ID查询设备详情")
    @GetMapping("/detailById")
    public Result<DevicePO> getDetailById(@RequestParam Integer id){
        DevicePO devicePo = deviceService.detailById(id);
        return Result.success(devicePo);
    }

    @ApiOperation("占用或释放设备")
    @PostMapping("/hold")
    public Result<?> deviceHold(@Valid @RequestBody DeviceHoldDTO deviceHoldDTO){
        boolean result = deviceService.deviceHold(deviceHoldDTO);
        return result ? Result.success() : Result.fail();
    }

    @ApiOperation("根据ID查询设备连接信息")
    @GetMapping("/getConnectionById")
    public Result<DeviceConnectionPO> getConnectionById(@RequestParam Integer id){
        DeviceConnectionPO connectionById = deviceConnectionService.getConnectionById(id);
        return Result.success(connectionById);
    }
}
