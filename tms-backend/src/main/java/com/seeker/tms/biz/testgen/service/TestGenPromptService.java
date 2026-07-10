package com.seeker.tms.biz.testgen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.seeker.tms.biz.testgen.model.PromptDTO;
import com.seeker.tms.biz.testgen.model.PromptPO;
import com.seeker.tms.biz.testgen.model.PromptQueryDTO;
import com.seeker.tms.biz.testgen.model.PromptStageVO;
import com.seeker.tms.biz.testgen.model.PromptVO;
import com.seeker.tms.common.entities.PageResult;

import java.util.List;

public interface TestGenPromptService extends IService<PromptPO> {

    PageResult<PromptVO> page(PromptQueryDTO query);

    /** 提示词详情,含从 MinIO 读取的内容 */
    PromptVO detail(Integer id);

    /**
     * 新增或编辑提示词:内容写入 MinIO。
     * 若 stageKey 非空且已被其他提示词占用,则接管该阶段(清空对方的 stageKey)。
     */
    Integer saveOrUpdate(PromptDTO dto);

    boolean remove(Integer id);

    /** 阶段字典,附带各阶段当前绑定的提示词信息(供前端下拉与接管提示) */
    List<PromptStageVO> listStages();

    /**
     * 解析某阶段生效的系统提示词:优先取 DB 标记提示词的 MinIO 内容,缺失时回退 classpath 静态文件。
     */
    String getSystemPrompt(String stageKey);
}
