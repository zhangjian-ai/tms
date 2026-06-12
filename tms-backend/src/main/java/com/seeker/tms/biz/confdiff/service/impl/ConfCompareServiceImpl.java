package com.seeker.tms.biz.confdiff.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.jcraft.jsch.Session;
import com.seeker.tms.biz.confdiff.entities.CommitInfo;
import com.seeker.tms.biz.confdiff.entities.CompareRequestDTO;
import com.seeker.tms.biz.confdiff.entities.ConfMachinePO;
import com.seeker.tms.biz.confdiff.entities.ConfProjectPO;
import com.seeker.tms.biz.confdiff.entities.PrepareStatusVO;
import com.seeker.tms.biz.confdiff.entities.compare.CompareHistoryVO;
import com.seeker.tms.biz.confdiff.entities.compare.CompareResultVO;
import com.seeker.tms.biz.confdiff.service.ConfCompareService;
import com.seeker.tms.biz.confdiff.service.ConfMachineService;
import com.seeker.tms.biz.confdiff.service.ConfProjectService;
import com.seeker.tms.biz.confdiff.support.ConfCompareExecutor;
import com.seeker.tms.biz.confdiff.support.ConfCompareHistoryStore;
import com.seeker.tms.biz.confdiff.support.ConfPrepareExecutor;
import com.seeker.tms.biz.confdiff.support.ConfPrepareStatusStore;
import com.seeker.tms.biz.confdiff.support.GitSnapshotService;
import com.seeker.tms.biz.confdiff.support.ProjectLockRegistry;
import com.seeker.tms.biz.confdiff.support.SshGitClient;
import com.seeker.tms.common.utils.MinioUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@AllArgsConstructor
public class ConfCompareServiceImpl implements ConfCompareService {

    private final ConfProjectService confProjectService;
    private final ConfMachineService confMachineService;
    private final GitSnapshotService gitSnapshotService;
    private final SshGitClient sshGitClient;
    private final MinioUtil minioUtil;
    private final ProjectLockRegistry lockRegistry;
    private final ConfPrepareExecutor prepareExecutor;
    private final ConfPrepareStatusStore prepareStatusStore;
    private final ConfCompareHistoryStore historyStore;
    private final ConfCompareExecutor compareExecutor;

    @Override
    public CompareResultVO compare(CompareRequestDTO request) {
        // 同步做基本校验,快速失败
        ConfProjectPO project = confProjectService.getById(request.getProjectId());
        if (project == null) {
            throw new IllegalArgumentException("无效的项目ID: " + request.getProjectId());
        }
        ConfMachinePO machine = confMachineService.getById(project.getMachineId());
        if (machine == null) {
            throw new IllegalArgumentException("项目所属机器不存在,machineId=" + project.getMachineId());
        }
        List<String> configPaths = StrUtil.split(project.getConfigPaths(), ',', true, true);
        if (configPaths.isEmpty()) {
            throw new IllegalArgumentException("项目未配置任何配置路径");
        }

        // 落地 RUNNING 占位并异步执行,立即返回 id
        CompareResultVO placeholder = new CompareResultVO();
        placeholder.setId(IdUtil.fastSimpleUUID());
        placeholder.setStatus(CompareResultVO.RUNNING);
        placeholder.setProjectId(project.getId());
        placeholder.setProjectName(project.getName());
        placeholder.setRefA(request.getRefA().display());
        placeholder.setRefB(request.getRefB().display());
        placeholder.setCreateTime(DateUtil.now());
        historyStore.create(placeholder);

        compareExecutor.runAsync(placeholder, request, machine, project, configPaths);
        return placeholder;
    }

    @Override
    public PrepareStatusVO prepare(Integer projectId) {
        ConfProjectPO project = confProjectService.getById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("无效的项目ID: " + projectId);
        }
        if (confMachineService.getById(project.getMachineId()) == null) {
            throw new IllegalArgumentException("项目所属机器不存在,machineId=" + project.getMachineId());
        }
        PrepareStatusVO cur = prepareStatusStore.get(projectId);
        if (cur != null && PrepareStatusVO.PREPARING.equals(cur.getStatus())) {
            return cur; // 已在准备中,避免重复触发
        }
        prepareStatusStore.set(projectId, PrepareStatusVO.PREPARING, "正在初始化(首次克隆),请稍候…");
        prepareExecutor.cloneAsync(projectId);
        return prepareStatusStore.get(projectId);
    }

    @Override
    public PrepareStatusVO prepareStatus(Integer projectId) {
        ConfProjectPO project = confProjectService.getById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("无效的项目ID: " + projectId);
        }
        PrepareStatusVO cur = prepareStatusStore.get(projectId);
        if (cur != null) {
            return cur;
        }
        // 无记录:快速探测远程是否已存在(可能是历史已 clone),并回写缓存
        ConfMachinePO machine = confMachineService.getById(project.getMachineId());
        if (machine == null) {
            return new PrepareStatusVO(projectId, PrepareStatusVO.NOT_PREPARED, "项目所属机器不存在");
        }
        boolean cloned;
        Session session = null;
        try {
            session = sshGitClient.openSession(machine);
            cloned = gitSnapshotService.isCloned(session, machine, project);
        } catch (Exception e) {
            // 探测失败(机器不可达等)不缓存,直接返回未准备 + 原因
            return new PrepareStatusVO(projectId, PrepareStatusVO.NOT_PREPARED, "检测失败: " + e.getMessage());
        } finally {
            sshGitClient.close(session);
        }
        String status = cloned ? PrepareStatusVO.READY : PrepareStatusVO.NOT_PREPARED;
        prepareStatusStore.set(projectId, status, cloned ? "已就绪" : "尚未克隆");
        return prepareStatusStore.get(projectId);
    }

    @Override
    public List<String> listBranches(Integer projectId) {
        return withProjectGit(projectId, (machine, project, session) ->
                gitSnapshotService.listBranches(session, machine, project));
    }

    @Override
    public List<CommitInfo> listCommits(Integer projectId, String branch, Integer limit) {
        if (StrUtil.isBlank(branch)) {
            throw new IllegalArgumentException("分支名不能为空");
        }
        int n = (limit == null || limit <= 0) ? 50 : Math.min(limit, 300);
        return withProjectGit(projectId, (machine, project, session) ->
                gitSnapshotService.listCommits(session, machine, project, branch.trim(), n));
    }

    /** 校验项目/机器,加项目锁,打开会话执行 git 读操作,完成后释放 */
    private <T> T withProjectGit(Integer projectId, GitAction<T> action) {
        ConfProjectPO project = confProjectService.getById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("无效的项目ID: " + projectId);
        }
        ConfMachinePO machine = confMachineService.getById(project.getMachineId());
        if (machine == null) {
            throw new IllegalArgumentException("项目所属机器不存在,machineId=" + project.getMachineId());
        }
        ReentrantLock lock = lockRegistry.get(projectId);
        lock.lock();
        Session session = null;
        try {
            session = sshGitClient.openSession(machine);
            return action.apply(machine, project, session);
        } finally {
            sshGitClient.close(session);
            lock.unlock();
        }
    }

    @FunctionalInterface
    private interface GitAction<T> {
        T apply(ConfMachinePO machine, ConfProjectPO project, Session session);
    }

    @Override
    public List<CompareHistoryVO> history(Integer projectId) {
        return historyStore.listByProject(projectId);
    }

    @Override
    public CompareResultVO getResult(String id) {
        CompareResultVO result = historyStore.get(id);
        if (result == null) {
            throw new IllegalArgumentException("对比结果不存在或已过期: " + id);
        }
        // 报告预签名链接可能已过期,基于对象 key 重新生成
        if (StrUtil.isNotBlank(result.getReportKey())) {
            try {
                result.setReportUrl(minioUtil.getUrl(result.getReportKey()));
            } catch (Exception e) {
                log.warn("刷新报告下载链接失败: {}", result.getReportKey());
            }
        }
        return result;
    }
}
