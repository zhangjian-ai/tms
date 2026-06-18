package com.seeker.tms.biz.confdiff.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jcraft.jsch.Session;
import com.seeker.tms.biz.confdiff.config.ConfDiffProperties;
import com.seeker.tms.biz.confdiff.entities.CompareRequestDTO;
import com.seeker.tms.biz.confdiff.entities.ConfMachinePO;
import com.seeker.tms.biz.confdiff.entities.ConfProjectPO;
import com.seeker.tms.biz.confdiff.entities.compare.CompareResultVO;
import com.seeker.tms.biz.confdiff.entities.compare.ContentCompare;
import com.seeker.tms.biz.confdiff.entities.compare.ContentCompare.FileContentDiff;
import com.seeker.tms.common.utils.MinioUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 异步执行配置对比。独立 Bean 以保证 @Async 代理生效,且不反向依赖 Service(避免循环依赖)。
 * 远程 git(clone 校验/fetch/checkout/下载)耗时较长,放后台线程执行,完成后回写历史。
 */
@Slf4j
@Component
@AllArgsConstructor
public class ConfCompareExecutor {

    private final GitSnapshotService gitSnapshotService;
    private final SshGitClient sshGitClient;
    private final MinioUtil minioUtil;
    private final ConfDiffProperties confDiffProperties;
    private final ProjectLockRegistry lockRegistry;
    private final ConfCompareHistoryStore historyStore;

    /**
     * 异步执行对比。result 为已落地的 RUNNING 占位(含 id/projectId/refs/createTime)。
     */
    @Async("taskExecutor")
    public void runAsync(CompareResultVO placeholder, CompareRequestDTO request,
                         ConfMachinePO machine, ConfProjectPO project, List<String> configPaths) {
        long start = System.currentTimeMillis();
        File groupDir = new File(workspaceRoot(), placeholder.getId());
        File snapA = new File(groupDir, "a");
        File snapB = new File(groupDir, "b");
        log.info("配置对比开始(异步): id={}, project={}, refA={}, refB={}",
                placeholder.getId(), project.getName(), request.getRefA().display(), request.getRefB().display());
        try {
            String[] resolved = downloadSnapshots(machine, project, configPaths, request, snapA, snapB);

            CompareResultVO result = buildResult(project, request, resolved, snapA, snapB);
            result.setId(placeholder.getId());
            result.setStatus(CompareResultVO.SUCCESS);
            result.setCreateTime(placeholder.getCreateTime());
            result.setElapsedMs(System.currentTimeMillis() - start);

            if (!result.isConsistent()) {
                uploadReport(result);
            }
            historyStore.update(result);
            log.info("配置对比完成(异步): id={}, consistent={}", result.getId(), result.isConsistent());
        } catch (Exception e) {
            log.error("配置对比失败(异步): id={}", placeholder.getId(), e);
            placeholder.setStatus(CompareResultVO.FAILED);
            placeholder.setMessage(e.getMessage());
            placeholder.setElapsedMs(System.currentTimeMillis() - start);
            historyStore.update(placeholder);
        } finally {
            FileUtil.del(groupDir);
        }
    }

    /** @return [refA实际commit, refB实际commit] */
    private String[] downloadSnapshots(ConfMachinePO machine, ConfProjectPO project, List<String> configPaths,
                                       CompareRequestDTO request, File snapA, File snapB) {
        ReentrantLock lock = lockRegistry.get(project.getId());
        lock.lock();
        Session session = null;
        try {
            session = sshGitClient.openSession(machine);
            // 必须先 A 后 B,因为 checkout 会覆盖远程工作树
            String commitA = gitSnapshotService.snapshot(session, machine, project, configPaths, request.getRefA(), snapA);
            String commitB = gitSnapshotService.snapshot(session, machine, project, configPaths, request.getRefB(), snapB);
            return new String[]{commitA, commitB};
        } finally {
            sshGitClient.close(session);
            lock.unlock();
        }
    }

    private CompareResultVO buildResult(ConfProjectPO project, CompareRequestDTO request,
                                        String[] resolved, File snapA, File snapB) {
        CompareResultVO result = new CompareResultVO();
        result.setProjectId(project.getId());
        result.setProjectName(project.getName());
        result.setRefA(request.getRefA().display());
        result.setRefB(request.getRefB().display());
        result.setResolvedCommitA(resolved[0]);
        result.setResolvedCommitB(resolved[1]);

        // 模块一:目录对比  模块二:文件对比
        result.setDirCompare(DirFileComparator.diffDirs(snapA, snapB));
        result.setFileCompare(DirFileComparator.diffFiles(snapA, snapB));

        // 模块三:文件内容对比(共有 excel 文件,md5 不同才解析逐行对比)
        ContentCompare content = new ContentCompare();
        for (String rel : DirFileComparator.changedExcelFiles(snapA, snapB)) {
            FileContentDiff fd = ExcelComparator.diff(new File(snapA, rel), new File(snapB, rel), rel);
            if (!fd.isEmpty()) {
                content.getFiles().add(fd);
            }
        }
        result.setContentCompare(content);

        result.setConsistent(result.getDirCompare().isEmpty()
                && result.getFileCompare().isEmpty()
                && result.getContentCompare().isEmpty());
        return result;
    }

    private void uploadReport(CompareResultVO result) {
        try {
            byte[] report = CompareReportWriter.write(result);
            String key = StrUtil.format("confdiff/{}/{}_vs_{}_{}.html",
                    result.getProjectId(), safe(result.getRefA()), safe(result.getRefB()),
                    System.currentTimeMillis());
            minioUtil.uploadFile(key, report, "text/html; charset=utf-8");
            result.setReportKey(key);
            result.setReportUrl(minioUtil.getUrl(key));
            result.setReportDownloadUrl(minioUtil.getDownloadUrl(key, baseName(key)));
        } catch (Exception e) {
            log.error("生成对比报告失败", e);
        }
    }

    private String safe(String ref) {
        return ref.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /** 取对象 key 的文件名部分,作为下载文件名 */
    private String baseName(String key) {
        int i = key.lastIndexOf('/');
        return i < 0 ? key : key.substring(i + 1);
    }

    /** 解析对比工作区根目录(项目内临时目录),不存在则创建 */
    private File workspaceRoot() {
        File root = new File(confDiffProperties.getWorkspace()).getAbsoluteFile();
        FileUtil.mkdir(root);
        return root;
    }
}
