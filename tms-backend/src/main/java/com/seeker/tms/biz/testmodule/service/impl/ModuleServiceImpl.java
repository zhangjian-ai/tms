package com.seeker.tms.biz.testmodule.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.seeker.tms.biz.testmodule.entities.ModuleAddDTO;
import com.seeker.tms.biz.testmodule.entities.ModulePO;
import com.seeker.tms.biz.testmodule.entities.ModuleUpdateDTO;
import com.seeker.tms.biz.testmodule.mapper.ModuleMapper;
import com.seeker.tms.biz.testmodule.service.ModuleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class ModuleServiceImpl extends ServiceImpl<ModuleMapper, ModulePO> implements ModuleService {
    @Override
    public Boolean addModule(ModuleAddDTO moduleAddDTO) {
        // 构建模块实体
        ModulePO modulePo = new ModulePO();
        modulePo.setName(moduleAddDTO.getName());
        modulePo.setParentId(moduleAddDTO.getParentId());
        modulePo.setIsProduct(moduleAddDTO.getIsProduct());
        modulePo.setCreateTime(LocalDateTime.now());
        modulePo.setUpdateTime(LocalDateTime.now());

        return this.save(modulePo);
    }

    @Override
    public Boolean deleteModule(ModuleUpdateDTO moduleUpdateDTO) {
        // 如果模块还有子模块则不能删除
        List<ModulePO> modulePOS = this.lambdaQuery().eq(ModulePO::getParentId, moduleUpdateDTO.getId()).list();
        if (modulePOS.size() > 0){
            log.error("当前模块下有子模块，禁止删除");
            return false;
        }

        return this.removeById(moduleUpdateDTO.getId());
    }

    @Override
    public Boolean updateModule(ModuleUpdateDTO moduleUpdateDTO) {
        ModulePO modulePo = this.getById(moduleUpdateDTO.getId());
        if (modulePo == null) {
            log.error("无效的模块ID: " + moduleUpdateDTO.getId().toString());
            return false;
        }

        // 数据更新
        return this.lambdaUpdate().eq(ModulePO::getId, moduleUpdateDTO.getId())
                .set(StrUtil.isNotBlank(moduleUpdateDTO.getName()), ModulePO::getName, moduleUpdateDTO.getName())
                .set(moduleUpdateDTO.getParentId() != null, ModulePO::getParentId, moduleUpdateDTO.getParentId())
                .set(moduleUpdateDTO.getIsProduct() != null, ModulePO::getIsProduct, moduleUpdateDTO.getIsProduct())
                .update();
    }

    @Override
    public List<ModulePO> listModule() {
        return this.list();
    }
}
