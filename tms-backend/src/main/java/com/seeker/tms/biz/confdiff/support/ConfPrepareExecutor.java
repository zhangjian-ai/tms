package com.seeker.tms.biz.confdiff.support;

import com.jcraft.jsch.Session;
import com.seeker.tms.biz.confdiff.entities.ConfMachinePO;
import com.seeker.tms.biz.confdiff.entities.ConfProjectPO;
import com.seeker.tms.biz.confdiff.entities.PrepareStatusVO;
import com.seeker.tms.biz.confdiff.service.ConfMachineService;
import com.seeker.tms.biz.confdiff.service.ConfProjectService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 异步执行首次 clone(准备项目)。独立 Bean 以保证 @Async 代理生效。
 */
@Slf4j
@Component
@AllArgsConstructor
public class ConfPrepareExecutor {

    private final ConfProjectService confProjectService;
    private final ConfMachineService confMachineService;
    private final GitSnapshotService gitSnapshotService;
    private final SshGitClient sshGitClient;
    private final ProjectLockRegistry lockRegistry;
    private final ConfPrepareStatusStore statusStore;

    @Async("taskExecutor")
    public void cloneAsync(Integer projectId) {
        ConfProjectPO project = confProjectService.getById(projectId);
        if (project == null) {
            statusStore.set(projectId, PrepareStatusVO.FAILED, "项目不存在");
            return;
        }
        ConfMachinePO machine = confMachineService.getById(project.getMachineId());
        if (machine == null) {
            statusStore.set(projectId, PrepareStatusVO.FAILED, "项目所属机器不存在");
            return;
        }

        ReentrantLock lock = lockRegistry.get(projectId);
        lock.lock();
        Session session = null;
        try {
            session = sshGitClient.openSession(machine);
            gitSnapshotService.cloneRepo(session, machine, project);
            statusStore.set(projectId, PrepareStatusVO.READY, "准备完成");
            log.info("项目准备完成: {}", project.getName());
        } catch (Exception e) {
            log.error("项目准备失败: {}", project.getName(), e);
            statusStore.set(projectId, PrepareStatusVO.FAILED, e.getMessage());
        } finally {
            sshGitClient.close(session);
            lock.unlock();
        }
    }
}
