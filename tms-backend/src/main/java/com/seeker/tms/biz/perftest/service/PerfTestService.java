package com.seeker.tms.biz.perftest.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.seeker.tms.biz.perftest.entities.PerfTestDTO;
import com.seeker.tms.biz.perftest.entities.PerfTestPO;


public interface PerfTestService extends IService<PerfTestPO> {

    boolean addTest(PerfTestDTO perfTestDTO);
}
