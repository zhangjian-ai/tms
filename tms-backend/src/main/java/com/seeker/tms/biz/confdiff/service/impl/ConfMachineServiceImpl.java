package com.seeker.tms.biz.confdiff.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jcraft.jsch.Session;
import com.seeker.tms.biz.confdiff.entities.ConfMachineDTO;
import com.seeker.tms.biz.confdiff.entities.ConfMachinePO;
import com.seeker.tms.biz.confdiff.entities.ConfMachineQueryDTO;
import com.seeker.tms.biz.confdiff.entities.ConfMachineVO;
import com.seeker.tms.biz.confdiff.enums.AuthType;
import com.seeker.tms.biz.confdiff.mapper.ConfMachineMapper;
import com.seeker.tms.biz.confdiff.service.ConfMachineService;
import com.seeker.tms.biz.confdiff.support.SshGitClient;
import com.seeker.tms.common.entities.PageResult;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
public class ConfMachineServiceImpl extends ServiceImpl<ConfMachineMapper, ConfMachinePO> implements ConfMachineService {

    private final SshGitClient sshGitClient;

    @Override
    public PageResult<ConfMachineVO> page(ConfMachineQueryDTO query) {
        Page<ConfMachinePO> page = Page.of(query.getPageNo(), query.getPageSize());
        if (StrUtil.isNotBlank(query.getSortBy())) {
            page.addOrder(new OrderItem(query.getSortBy(), query.isAsc()));
        } else {
            page.addOrder(new OrderItem("update_time", query.isAsc()));
        }

        this.lambdaQuery()
                .like(StrUtil.isNotBlank(query.getName()), ConfMachinePO::getName, query.getName())
                .like(StrUtil.isNotBlank(query.getHost()), ConfMachinePO::getHost, query.getHost())
                .page(page);

        PageResult<ConfMachineVO> result = new PageResult<>();
        result.setTotal((int) page.getTotal());
        result.setPageNo((int) page.getCurrent());
        result.setPageCount((int) page.getPages());
        result.setList(page.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return result;
    }

    @Override
    public ConfMachineVO detail(Integer id) {
        ConfMachinePO po = this.getById(id);
        if (po == null) {
            throw new IllegalArgumentException("无效的机器ID: " + id);
        }
        return toVO(po);
    }

    @Override
    public Integer saveOrUpdateMachine(ConfMachineDTO dto) {
        validateAuth(dto);
        ConfMachinePO po = BeanUtil.copyProperties(dto, ConfMachinePO.class);
        if (po.getPort() == null) po.setPort(22);
        this.saveOrUpdate(po);
        return po.getId();
    }

    @Override
    public boolean removeMachine(Integer id) {
        // 关联项目通过外键 ON DELETE CASCADE 级联删除
        return this.removeById(id);
    }

    @Override
    public boolean testConnection(Integer id) {
        ConfMachinePO machine = this.getById(id);
        if (machine == null) {
            throw new IllegalArgumentException("无效的机器ID: " + id);
        }
        Session session = null;
        try {
            session = sshGitClient.openSession(machine);
            String out = sshGitClient.exec(session, "git --version");
            log.info("机器[{}]连通性测试: {}", machine.getName(), out.trim());
            return true;
        } finally {
            sshGitClient.close(session);
        }
    }

    private void validateAuth(ConfMachineDTO dto) {
        if (dto.getAuthType() == AuthType.PASSWORD && StrUtil.isBlank(dto.getPassword())) {
            throw new IllegalArgumentException("密码鉴权方式下密码不能为空");
        }
        if (dto.getAuthType() == AuthType.PRIVATE_KEY && StrUtil.isBlank(dto.getPrivateKey())) {
            throw new IllegalArgumentException("私钥鉴权方式下私钥内容不能为空");
        }
    }

    private ConfMachineVO toVO(ConfMachinePO po) {
        ConfMachineVO vo = BeanUtil.copyProperties(po, ConfMachineVO.class);
        vo.setHasPassword(StrUtil.isNotBlank(po.getPassword()));
        vo.setHasPrivateKey(StrUtil.isNotBlank(po.getPrivateKey()));
        return vo;
    }
}
