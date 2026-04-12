package com.seeker.tms.biz.perftest.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.seeker.tms.biz.perftest.entities.PerfTestDTO;
import com.seeker.tms.biz.perftest.entities.PerfTestPO;
import com.seeker.tms.biz.perftest.mapper.PerfTestMapper;
import com.seeker.tms.biz.perftest.service.PerfTestService;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class PerfTestServiceImpl extends ServiceImpl<PerfTestMapper, PerfTestPO> implements PerfTestService {

    @Override
    public boolean addTest(PerfTestDTO perfTestDTO) {

        System.out.println(JSON.toJSONString(perfTestDTO));

        PerfTestPO perfTestPO = BeanUtil.copyProperties(perfTestDTO, PerfTestPO.class);
        perfTestPO.setCreateTime(LocalDateTime.now());
        perfTestPO.setUpdateTime(LocalDateTime.now());

        return save(perfTestPO);

    }
}
