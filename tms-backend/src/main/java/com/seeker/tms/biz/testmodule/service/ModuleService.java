package com.seeker.tms.biz.testmodule.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.seeker.tms.biz.testmodule.entities.ModuleAddDTO;
import com.seeker.tms.biz.testmodule.entities.ModulePO;
import com.seeker.tms.biz.testmodule.entities.ModuleUpdateDTO;


import java.util.List;

public interface ModuleService extends IService<ModulePO> {

    Boolean addModule(ModuleAddDTO moduleAddDTO);

    Boolean deleteModule(ModuleUpdateDTO moduleUpdateDTO);

    Boolean updateModule(ModuleUpdateDTO moduleUpdateDTO);

    List<ModulePO> listModule();
}
