package com.seeker.tms.biz.testgen.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.seeker.tms.biz.testgen.mapper.PromptMapper;
import com.seeker.tms.biz.testgen.mapper.PromptStageMapper;
import com.seeker.tms.biz.testgen.model.PromptDTO;
import com.seeker.tms.biz.testgen.model.PromptPO;
import com.seeker.tms.biz.testgen.model.PromptQueryDTO;
import com.seeker.tms.biz.testgen.model.PromptStagePO;
import com.seeker.tms.biz.testgen.model.PromptStageVO;
import com.seeker.tms.biz.testgen.model.PromptVO;
import com.seeker.tms.biz.testgen.service.TestGenPromptService;
import com.seeker.tms.biz.testgen.utils.PromptLoader;
import com.seeker.tms.common.entities.PageResult;
import com.seeker.tms.common.utils.MinioUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestGenPromptServiceImpl extends ServiceImpl<PromptMapper, PromptPO>
        implements TestGenPromptService, ApplicationRunner {

    private final PromptStageMapper stageMapper;
    private final MinioUtil minioUtil;

    private static final String OBJECT_PREFIX = "testgen/prompts/";
    private static final String CONTENT_TYPE = "text/plain; charset=utf-8";

    // ---------------- 运行时解析 ----------------

    /**
     * 解析某阶段的系统提示词:优先取 DB 标记提示词的 MinIO 内容,缺失时回退 classpath 静态文件。
     */
    @Override
    public String getSystemPrompt(String stageKey) {
        String content = null;
        PromptPO po = this.lambdaQuery().eq(PromptPO::getStageKey, stageKey).one();
        if (po != null && StrUtil.isNotBlank(po.getObjectKey())) {
            try {
                content = minioUtil.getContent(po.getObjectKey());
            } catch (Exception e) {
                log.warn("读取提示词内容失败,回退静态文件: stageKey={}, objectKey={}", stageKey, po.getObjectKey(), e);
            }
        }
        if (StrUtil.isBlank(content)) {
            // classpath 静态文件兜底(文件名与 stageKey 严格一致)
            content = PromptLoader.load(stageKey);
        }
        return content;
    }

    // ---------------- CRUD ----------------

    @Override
    public PageResult<PromptVO> page(PromptQueryDTO query) {
        Page<PromptPO> page = Page.of(query.getPageNo(), query.getPageSize());
        if (StrUtil.isNotBlank(query.getSortBy())) {
            page.addOrder(new OrderItem(query.getSortBy(), query.isAsc()));
        } else {
            page.addOrder(new OrderItem("id", query.isAsc()));
        }

        this.lambdaQuery()
                .like(StrUtil.isNotBlank(query.getName()), PromptPO::getName, query.getName())
                .eq(StrUtil.isNotBlank(query.getStageKey()), PromptPO::getStageKey, query.getStageKey())
                .page(page);

        Map<String, String> stageNames = stageNameMap();
        PageResult<PromptVO> result = new PageResult<>();
        result.setTotal((int) page.getTotal());
        result.setPageNo((int) page.getCurrent());
        result.setPageCount((int) page.getPages());
        result.setList(page.getRecords().stream()
                .map(po -> toVO(po, stageNames, false))
                .collect(Collectors.toList()));
        return result;
    }

    @Override
    public PromptVO detail(Integer id) {
        PromptPO po = this.getById(id);
        if (po == null) {
            throw new IllegalArgumentException("无效的提示词ID: " + id);
        }
        PromptVO vo = toVO(po, stageNameMap(), false);
        // 内容读取失败时回退静态文件(草稿无 stageKey 则返回空)
        try {
            vo.setContent(minioUtil.getContent(po.getObjectKey()));
        } catch (Exception e) {
            log.warn("详情读取提示词内容失败: id={}, objectKey={}", id, po.getObjectKey(), e);
            if (StrUtil.isNotBlank(po.getStageKey())) {
                vo.setContent(PromptLoader.load(po.getStageKey()));
            }
        }
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer saveOrUpdate(PromptDTO dto) {
        String stageKey = StrUtil.isBlank(dto.getStageKey()) ? null : dto.getStageKey().trim();
        if (stageKey != null && stageMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PromptStagePO>()
                        .eq(PromptStagePO::getStageKey, stageKey)) == 0) {
            throw new IllegalArgumentException("无效的阶段: " + stageKey);
        }

        PromptPO po;
        if (dto.getId() != null) {
            po = this.getById(dto.getId());
            if (po == null) {
                throw new IllegalArgumentException("无效的提示词ID: " + dto.getId());
            }
        } else {
            po = new PromptPO();
        }

        // 接管:先清空其他占用同一阶段的提示词,避免唯一键冲突
        if (stageKey != null) {
            this.lambdaUpdate()
                    .ne(dto.getId() != null, PromptPO::getId, dto.getId())
                    .eq(PromptPO::getStageKey, stageKey)
                    .set(PromptPO::getStageKey, null)
                    .update();
        }

        // 对象键:编辑复用原键,新增时生成(不依赖自增id,避免为拿id先插入空 object_key 违反非空约束)
        String objectKey = StrUtil.isBlank(po.getObjectKey())
                ? OBJECT_PREFIX + "prompt_" + UUID.randomUUID().toString().replace("-", "") + ".txt"
                : po.getObjectKey();
        // 先写内容到 MinIO,再落库
        minioUtil.uploadFile(objectKey, dto.getContent().getBytes(StandardCharsets.UTF_8), CONTENT_TYPE);

        po.setName(dto.getName());
        po.setStageKey(stageKey);
        po.setRemark(dto.getRemark());
        po.setObjectKey(objectKey);
        super.saveOrUpdate(po);

        return po.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean remove(Integer id) {
        PromptPO po = this.getById(id);
        if (po == null) {
            return false;
        }
        boolean removed = this.removeById(id);
        if (removed && StrUtil.isNotBlank(po.getObjectKey())) {
            try {
                minioUtil.deleteFile(po.getObjectKey());
            } catch (Exception e) {
                log.warn("删除提示词内容失败(忽略): objectKey={}", po.getObjectKey(), e);
            }
        }
        return removed;
    }

    @Override
    public List<PromptStageVO> listStages() {
        List<PromptStagePO> stages = stageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PromptStagePO>()
                        .orderByAsc(PromptStagePO::getSortNo));
        // 各阶段当前绑定的提示词
        Map<String, PromptPO> bound = this.lambdaQuery()
                .isNotNull(PromptPO::getStageKey)
                .list().stream()
                .collect(Collectors.toMap(PromptPO::getStageKey, p -> p, (a, b) -> a));

        return stages.stream().map(s -> {
            PromptStageVO vo = new PromptStageVO();
            vo.setStageKey(s.getStageKey());
            vo.setStageName(s.getStageName());
            vo.setDescription(s.getDescription());
            vo.setSortNo(s.getSortNo());
            PromptPO p = bound.get(s.getStageKey());
            if (p != null) {
                vo.setBoundPromptId(p.getId());
                vo.setBoundPromptName(p.getName());
            }
            return vo;
        }).collect(Collectors.toList());
    }

    // ---------------- 启动导入 ----------------

    /** 启动时把工程内静态系统提示词幂等导入 MinIO 并标记到对应阶段 */
    @Override
    public void run(ApplicationArguments args) {
        List<PromptStagePO> stages = stageMapper.selectList(null);
        for (PromptStagePO stage : stages) {
            String stageKey = stage.getStageKey();
            try {
                boolean exists = this.lambdaQuery().eq(PromptPO::getStageKey, stageKey).count() > 0;
                if (exists) {
                    continue;
                }
                String content = PromptLoader.load(stageKey);
                // 先上传内容再落库,避免残留无内容的记录
                String objectKey = OBJECT_PREFIX + "seed_" + stageKey + ".txt";
                minioUtil.uploadFile(objectKey, content.getBytes(StandardCharsets.UTF_8), CONTENT_TYPE);
                PromptPO po = new PromptPO();
                po.setName(stage.getStageName() + " ·[官方]");
                po.setStageKey(stageKey);
                po.setObjectKey(objectKey);
                po.setRemark("系统内置默认提示词(启动时导入)");
                super.save(po);
                log.info("导入默认提示词: stageKey={}, id={}", stageKey, po.getId());
            } catch (Exception e) {
                log.warn("导入默认提示词失败(将回退静态文件): stageKey={}", stageKey, e);
            }
        }
    }

    // ---------------- helpers ----------------

    private Map<String, String> stageNameMap() {
        return stageMapper.selectList(null).stream()
                .collect(Collectors.toMap(PromptStagePO::getStageKey, PromptStagePO::getStageName, (a, b) -> a));
    }

    private PromptVO toVO(PromptPO po, Map<String, String> stageNames, boolean withContent) {
        PromptVO vo = new PromptVO();
        vo.setId(po.getId());
        vo.setName(po.getName());
        vo.setStageKey(po.getStageKey());
        vo.setStageName(po.getStageKey() == null ? null : stageNames.get(po.getStageKey()));
        vo.setRemark(po.getRemark());
        vo.setCreateTime(po.getCreateTime());
        vo.setUpdateTime(po.getUpdateTime());
        return vo;
    }
}
