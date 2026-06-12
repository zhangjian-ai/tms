package com.seeker.tms.biz.confdiff.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.seeker.tms.biz.confdiff.entities.ConfMachinePO;
import com.seeker.tms.biz.confdiff.entities.ConfProjectDTO;
import com.seeker.tms.biz.confdiff.entities.ConfProjectPO;
import com.seeker.tms.biz.confdiff.entities.ConfProjectQueryDTO;
import com.seeker.tms.biz.confdiff.entities.ConfProjectVO;
import com.seeker.tms.biz.confdiff.mapper.ConfProjectMapper;
import com.seeker.tms.biz.confdiff.service.ConfMachineService;
import com.seeker.tms.biz.confdiff.service.ConfProjectService;
import com.seeker.tms.common.entities.PageResult;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
public class ConfProjectServiceImpl extends ServiceImpl<ConfProjectMapper, ConfProjectPO> implements ConfProjectService {

    private final ConfMachineService confMachineService;

    @Override
    public PageResult<ConfProjectVO> page(ConfProjectQueryDTO query) {
        Page<ConfProjectPO> page = Page.of(query.getPageNo(), query.getPageSize());
        if (StrUtil.isNotBlank(query.getSortBy())) {
            page.addOrder(new OrderItem(query.getSortBy(), query.isAsc()));
        } else {
            page.addOrder(new OrderItem("update_time", query.isAsc()));
        }

        this.lambdaQuery()
                .eq(query.getMachineId() != null, ConfProjectPO::getMachineId, query.getMachineId())
                .like(StrUtil.isNotBlank(query.getName()), ConfProjectPO::getName, query.getName())
                .page(page);

        List<ConfProjectPO> records = page.getRecords();
        Map<Integer, String> machineNames = loadMachineNames(records);

        PageResult<ConfProjectVO> result = new PageResult<>();
        result.setTotal((int) page.getTotal());
        result.setPageNo((int) page.getCurrent());
        result.setPageCount((int) page.getPages());
        result.setList(records.stream().map(po -> toVO(po, machineNames.get(po.getMachineId()))).collect(Collectors.toList()));
        return result;
    }

    @Override
    public ConfProjectVO detail(Integer id) {
        ConfProjectPO po = this.getById(id);
        if (po == null) {
            throw new IllegalArgumentException("无效的项目ID: " + id);
        }
        ConfMachinePO machine = confMachineService.getById(po.getMachineId());
        return toVO(po, machine == null ? null : machine.getName());
    }

    @Override
    public Integer saveOrUpdateProject(ConfProjectDTO dto) {
        if (confMachineService.getById(dto.getMachineId()) == null) {
            throw new IllegalArgumentException("无效的机器ID: " + dto.getMachineId());
        }
        ConfProjectPO po = BeanUtil.copyProperties(dto, ConfProjectPO.class, "configPaths");
        po.setConfigPaths(joinPaths(dto.getConfigPaths()));
        if (StrUtil.isBlank(po.getDefaultBranch())) po.setDefaultBranch("master");
        this.saveOrUpdate(po);
        return po.getId();
    }

    @Override
    public boolean removeProject(Integer id) {
        return this.removeById(id);
    }

    @Override
    public List<ConfProjectVO> listByMachine(Integer machineId) {
        ConfMachinePO machine = confMachineService.getById(machineId);
        String machineName = machine == null ? null : machine.getName();
        return this.lambdaQuery()
                .eq(ConfProjectPO::getMachineId, machineId)
                .list().stream()
                .map(po -> toVO(po, machineName))
                .collect(Collectors.toList());
    }

    private Map<Integer, String> loadMachineNames(List<ConfProjectPO> records) {
        Set<Integer> machineIds = records.stream().map(ConfProjectPO::getMachineId).collect(Collectors.toSet());
        Map<Integer, String> map = new HashMap<>();
        if (!machineIds.isEmpty()) {
            confMachineService.listByIds(machineIds).forEach(m -> map.put(m.getId(), m.getName()));
        }
        return map;
    }

    private ConfProjectVO toVO(ConfProjectPO po, String machineName) {
        ConfProjectVO vo = BeanUtil.copyProperties(po, ConfProjectVO.class, "configPaths");
        vo.setConfigPaths(parsePaths(po.getConfigPaths()));
        vo.setMachineName(machineName);
        return vo;
    }

    private String joinPaths(List<String> paths) {
        if (CollUtil.isEmpty(paths)) return "";
        return paths.stream().map(String::trim).filter(StrUtil::isNotBlank).collect(Collectors.joining(","));
    }

    private List<String> parsePaths(String configPaths) {
        return StrUtil.split(configPaths, ',', true, true);
    }
}
