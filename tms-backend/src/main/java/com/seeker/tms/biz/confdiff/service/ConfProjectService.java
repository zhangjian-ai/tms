package com.seeker.tms.biz.confdiff.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.seeker.tms.biz.confdiff.entities.ConfProjectDTO;
import com.seeker.tms.biz.confdiff.entities.ConfProjectPO;
import com.seeker.tms.biz.confdiff.entities.ConfProjectQueryDTO;
import com.seeker.tms.biz.confdiff.entities.ConfProjectVO;
import com.seeker.tms.common.entities.PageResult;

import java.util.List;

public interface ConfProjectService extends IService<ConfProjectPO> {

    PageResult<ConfProjectVO> page(ConfProjectQueryDTO query);

    ConfProjectVO detail(Integer id);

    Integer saveOrUpdateProject(ConfProjectDTO dto);

    boolean removeProject(Integer id);

    List<ConfProjectVO> listByMachine(Integer machineId);
}
