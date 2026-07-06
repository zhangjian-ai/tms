package com.seeker.tms.biz.testgen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.seeker.tms.biz.testgen.model.AiModelDTO;
import com.seeker.tms.biz.testgen.model.AiModelPO;
import com.seeker.tms.biz.testgen.model.AiModelQueryDTO;
import com.seeker.tms.biz.testgen.model.AiModelVO;
import com.seeker.tms.biz.testgen.model.ModelConfig;
import com.seeker.tms.common.entities.PageResult;

public interface AiModelService extends IService<AiModelPO> {

    PageResult<AiModelVO> page(AiModelQueryDTO query);

    AiModelVO detail(Integer id);

    Integer saveOrUpdateModel(AiModelDTO dto);

    /**
     * 快速标记某模型的角色(thinking/vision)。
     * marked=true 时,同一角色在其他模型上的标记会被自动取消(全局唯一生效)。
     */
    void mark(Integer id, String role, boolean marked);

    boolean removeModel(Integer id);

    /** 解析当前生效的 thinking 模型配置,缺失时抛异常 */
    ModelConfig getThinking();

    /** 解析当前生效的 vision 模型配置,缺失时抛异常 */
    ModelConfig getVision();

    /**
     * 校验用例生成所需模型是否已配置(标记生效)。
     * thinking 恒为必需;needVision=true 时 vision 也必需。缺失则抛出可读异常。
     */
    void ensureAvailable(boolean needVision);
}
