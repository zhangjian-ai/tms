package com.seeker.tms.biz.confdiff.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.seeker.tms.biz.confdiff.entities.ConfMachineDTO;
import com.seeker.tms.biz.confdiff.entities.ConfMachinePO;
import com.seeker.tms.biz.confdiff.entities.ConfMachineQueryDTO;
import com.seeker.tms.biz.confdiff.entities.ConfMachineVO;
import com.seeker.tms.common.entities.PageResult;

public interface ConfMachineService extends IService<ConfMachinePO> {

    PageResult<ConfMachineVO> page(ConfMachineQueryDTO query);

    ConfMachineVO detail(Integer id);

    Integer saveOrUpdateMachine(ConfMachineDTO dto);

    boolean removeMachine(Integer id);

    /** 测试 SSH 连通性 */
    boolean testConnection(Integer id);
}
