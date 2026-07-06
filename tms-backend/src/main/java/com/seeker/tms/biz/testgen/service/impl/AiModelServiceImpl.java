package com.seeker.tms.biz.testgen.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.seeker.tms.biz.testgen.mapper.AiModelMapper;
import com.seeker.tms.biz.testgen.model.AiModelDTO;
import com.seeker.tms.biz.testgen.model.AiModelPO;
import com.seeker.tms.biz.testgen.model.AiModelQueryDTO;
import com.seeker.tms.biz.testgen.model.AiModelVO;
import com.seeker.tms.biz.testgen.model.ModelConfig;
import com.seeker.tms.biz.testgen.service.AiModelService;
import com.seeker.tms.common.entities.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AiModelServiceImpl extends ServiceImpl<AiModelMapper, AiModelPO> implements AiModelService {

    @Override
    public PageResult<AiModelVO> page(AiModelQueryDTO query) {
        Page<AiModelPO> page = Page.of(query.getPageNo(), query.getPageSize());
        if (StrUtil.isNotBlank(query.getSortBy())) {
            page.addOrder(new OrderItem(query.getSortBy(), query.isAsc()));
        } else {
            page.addOrder(new OrderItem("update_time", query.isAsc()));
        }

        this.lambdaQuery()
                .like(StrUtil.isNotBlank(query.getName()), AiModelPO::getName, query.getName())
                .page(page);

        PageResult<AiModelVO> result = new PageResult<>();
        result.setTotal((int) page.getTotal());
        result.setPageNo((int) page.getCurrent());
        result.setPageCount((int) page.getPages());
        result.setList(page.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return result;
    }

    @Override
    public AiModelVO detail(Integer id) {
        AiModelPO po = this.getById(id);
        if (po == null) {
            throw new IllegalArgumentException("无效的模型ID: " + id);
        }
        return toVO(po);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer saveOrUpdateModel(AiModelDTO dto) {
        AiModelPO po = BeanUtil.copyProperties(dto, AiModelPO.class);

        if (dto.getId() != null) {
            AiModelPO old = this.getById(dto.getId());
            if (old == null) {
                throw new IllegalArgumentException("无效的模型ID: " + dto.getId());
            }
            // 角色标记由列表快速开关(mark)管理,编辑保存时一律沿用原值,避免被清除
            po.setUseAsThinking(old.getUseAsThinking());
            po.setUseAsVision(old.getUseAsVision());
            // 密钥留空表示不修改,回填原值
            if (StrUtil.isBlank(dto.getApiKey())) {
                po.setApiKey(old.getApiKey());
            }
        } else {
            // 新增:默认不标记,密钥必填
            po.setUseAsThinking(false);
            po.setUseAsVision(false);
            if (StrUtil.isBlank(po.getApiKey())) {
                throw new IllegalArgumentException("新增模型时密钥不能为空");
            }
        }

        this.saveOrUpdate(po);
        return po.getId();
    }

    /** 把除 keepId 外所有行的对应角色标记清为 false */
    private void clearRoleOnOthers(Integer keepId, boolean thinking, boolean vision) {
        this.lambdaUpdate()
                .ne(AiModelPO::getId, keepId)
                .eq(thinking, AiModelPO::getUseAsThinking, true)
                .eq(vision, AiModelPO::getUseAsVision, true)
                .set(thinking, AiModelPO::getUseAsThinking, false)
                .set(vision, AiModelPO::getUseAsVision, false)
                .update();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void mark(Integer id, String role, boolean marked) {
        AiModelPO po = this.getById(id);
        if (po == null) {
            throw new IllegalArgumentException("无效的模型ID: " + id);
        }
        boolean thinking = "thinking".equalsIgnoreCase(role);
        boolean vision = "vision".equalsIgnoreCase(role);
        if (!thinking && !vision) {
            throw new IllegalArgumentException("无效的角色: " + role + ",仅支持 thinking / vision");
        }

        if (thinking) {
            po.setUseAsThinking(marked);
        } else {
            po.setUseAsVision(marked);
        }
        this.updateById(po);

        // 标记生效时清除其他行同一角色的标记(全局唯一生效)
        if (marked) {
            clearRoleOnOthers(id, thinking, vision);
        }
    }

    @Override
    public boolean removeModel(Integer id) {
        return this.removeById(id);
    }

    @Override
    public ModelConfig getThinking() {
        return resolve(true);
    }

    @Override
    public ModelConfig getVision() {
        return resolve(false);
    }

    /** 查生效模型:对应角色标记=1,取最新一条 */
    private ModelConfig resolve(boolean thinking) {
        AiModelPO po = this.lambdaQuery()
                .eq(thinking, AiModelPO::getUseAsThinking, true)
                .eq(!thinking, AiModelPO::getUseAsVision, true)
                .orderByDesc(AiModelPO::getUpdateTime)
                .last("limit 1")
                .one();
        String role = thinking ? "thinking" : "vision";
        if (po == null) {
            throw new IllegalStateException("未配置生效的 " + role + " 模型,请在「系统配置-模型管理」中标记一个模型");
        }
        return new ModelConfig(po.getBaseUrl(), po.getApiKey(), po.getModelName());
    }

    @Override
    public void ensureAvailable(boolean needVision) {
        List<String> missing = new ArrayList<>();
        if (!hasRole(true)) missing.add("thinking");
        if (needVision && !hasRole(false)) missing.add("vision");
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "用例生成缺少可用的模型配置：" + String.join("、", missing)
                            + " 模型未标记，请先在「系统管理-模型管理」中标记生效模型");
        }
    }

    /** 是否存在对应角色的生效模型 */
    private boolean hasRole(boolean thinking) {
        return this.lambdaQuery()
                .eq(thinking, AiModelPO::getUseAsThinking, true)
                .eq(!thinking, AiModelPO::getUseAsVision, true)
                .count() > 0;
    }

    private AiModelVO toVO(AiModelPO po) {
        AiModelVO vo = BeanUtil.copyProperties(po, AiModelVO.class);
        vo.setHasApiKey(StrUtil.isNotBlank(po.getApiKey()));
        return vo;
    }
}
