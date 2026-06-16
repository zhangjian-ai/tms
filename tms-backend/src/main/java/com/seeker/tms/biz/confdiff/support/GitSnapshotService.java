package com.seeker.tms.biz.confdiff.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jcraft.jsch.Session;
import com.seeker.tms.biz.confdiff.entities.CommitInfo;
import com.seeker.tms.biz.confdiff.entities.CompareRef;
import com.seeker.tms.biz.confdiff.entities.ConfMachinePO;
import com.seeker.tms.biz.confdiff.entities.ConfProjectPO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 在远程机器上把"某分支上的某个 commit(可选)"准备到工作树,
 * 并把配置路径子树 SFTP 下载到本地目录。
 */
@Slf4j
@Service
@AllArgsConstructor
public class GitSnapshotService {

    private final SshGitClient sshGitClient;

    /**
     * git 联网命令(clone/fetch)的环境前缀:
     * - GIT_TERMINAL_PROMPT=0  禁止 https 账号/密码交互提示
     * - StrictHostKeyChecking=accept-new  非交互会话自动接受新主机密钥,避免 host key 校验失败导致 exit=128
     * - BatchMode=yes  任何 SSH 提示直接失败而非挂死(便于快速暴露真实错误)
     */
    private static final String GIT_NET_ENV =
            "GIT_TERMINAL_PROMPT=0 GIT_SSH_COMMAND='ssh -o StrictHostKeyChecking=accept-new -o BatchMode=yes' ";

    /** 首次 clone 超时(大仓库较慢) */
    private static final long CLONE_TIMEOUT_MS = 30 * 60 * 1000L;
    /** fetch 超时 */
    private static final long FETCH_TIMEOUT_MS = 10 * 60 * 1000L;

    /**
     * 准备工作树并下载配置路径子树。目录的创建与清理由调用方(按 group)负责。
     *
     * @param session     已打开的 SSH 会话(复用,避免重复握手)
     * @param machine     机器配置(提供 workDir)
     * @param project     项目配置
     * @param configPaths 解析后的配置相对路径列表
     * @param ref         分支 + 该分支上的 commit(commit 可空,空则取分支最新)
     * @param targetDir   本地目标目录,下载内容按 configPath 相对结构存放于此
     * @return 实际检出的提交完整 hash(便于追溯)
     */
    public String snapshot(Session session, ConfMachinePO machine, ConfProjectPO project,
                           List<String> configPaths, CompareRef ref, File targetDir) {
        String repoDir = trimTrailingSlash(machine.getWorkDir()) + "/" + project.getName();

        // 1. 要求已初始化(首次 clone 走异步"准备项目")
        requireCloned(session, machine, project);

        // 2. 重置工作树并更新到最新(保证幂等、可回滚)
        sshGitClient.exec(session, StrUtil.format("cd {} && git reset --hard && git clean -fd", quote(repoDir)));
        fetch(session, repoDir);

        // 3. 切到目标分支(确保分支正确)并按需回退到指定 commit
        checkout(session, repoDir, ref);

        // 4. 记录实际检出的提交
        String resolvedCommit = sshGitClient.exec(session,
                StrUtil.format("cd {} && git rev-parse HEAD", quote(repoDir))).trim();

        // 5. 逐个配置路径 SFTP 下载到本地目标目录
        FileUtil.mkdir(targetDir);
        for (String path : configPaths) {
            String remotePath = repoDir + "/" + trimSlash(path);
            File localPath = new File(targetDir, trimSlash(path));
            sshGitClient.downloadDir(session, remotePath, localPath);
        }
        log.info("快照已下载: project={}, ref={}, commit={}, dir={}",
                project.getName(), ref.display(), resolvedCommit, targetDir.getAbsolutePath());
        return resolvedCommit;
    }


    /** 远程仓库是否已 clone(快速检查) */
    public boolean isCloned(Session session, ConfMachinePO machine, ConfProjectPO project) {
        String repoDir = trimTrailingSlash(machine.getWorkDir()) + "/" + project.getName();
        String out = sshGitClient.exec(session, StrUtil.format(
                "[ -d {}/.git ] && echo OK || echo NO", quote(repoDir)));
        return out.contains("OK");
    }

    /**
     * 首次准备:仓库不存在则 clone(耗时较长),已存在则跳过。用于异步"准备项目"。
     */
    public void cloneRepo(Session session, ConfMachinePO machine, ConfProjectPO project) {
        String repoDir = trimTrailingSlash(machine.getWorkDir()) + "/" + project.getName();
        if (isCloned(session, machine, project)) {
            log.info("项目已存在,跳过clone: {}", repoDir);
            fetch(session, repoDir);
            return;
        }
        log.info("开始clone项目: {} -> {}", project.getRepoUrl(), repoDir);
        sshGitClient.exec(session, StrUtil.format("{}git clone {} {}",
                GIT_NET_ENV, quote(project.getRepoUrl()), quote(repoDir)), CLONE_TIMEOUT_MS);
        log.info("clone完成: {}", repoDir);
    }

    /** 要求仓库已存在,否则提示先准备(避免在请求链路里做耗时 clone) */
    private void requireCloned(Session session, ConfMachinePO machine, ConfProjectPO project) {
        if (!isCloned(session, machine, project)) {
            throw new RuntimeException("项目尚未初始化,请先在页面点击\"准备项目\"完成首次克隆后再操作");
        }
    }

    /** 更新远程引用(联网),带非交互环境前缀。同时拉取 tag */
    private void fetch(Session session, String repoDir) {
        sshGitClient.exec(session, StrUtil.format(
                "cd {} && {}git fetch --all --prune --tags", quote(repoDir), GIT_NET_ENV), FETCH_TIMEOUT_MS);
    }

    /**
     * 列出项目的远程分支(克隆并 fetch 后,读取 origin/* 分支,去掉 origin/ 前缀,排除 HEAD)。
     */
    public List<String> listBranches(Session session, ConfMachinePO machine, ConfProjectPO project) {
        String repoDir = trimTrailingSlash(machine.getWorkDir()) + "/" + project.getName();
        requireCloned(session, machine, project);
        fetch(session, repoDir);
        String out = sshGitClient.exec(session, StrUtil.format(
                "cd {} && git branch -r --format='%(refname:short)'", quote(repoDir)));
        List<String> branches = new ArrayList<>();
        for (String line : out.split("\\r?\\n")) {
            String b = line.trim();
            if (b.isEmpty() || b.contains("->")) continue; // 跳过 origin/HEAD -> origin/xxx
            b = StrUtil.removePrefix(b, "origin/");
            if (!b.isEmpty() && !"HEAD".equals(b)) branches.add(b);
        }
        return branches;
    }

    /**
     * 列出某分支上的提交(克隆并 fetch 后,git log origin/分支)。
     *
     * @param limit 最多返回条数
     */
    public List<CommitInfo> listCommits(Session session, ConfMachinePO machine, ConfProjectPO project,
                                        String branch, int limit) {
        String repoDir = trimTrailingSlash(machine.getWorkDir()) + "/" + project.getName();
        requireCloned(session, machine, project);
        fetch(session, repoDir);
        try {
            sshGitClient.exec(session, StrUtil.format(
                    "cd {} && git show-ref --verify --quiet refs/remotes/origin/{}", quote(repoDir), branch));
        } catch (RuntimeException e) {
            throw new RuntimeException("分支不存在: " + branch, e);
        }
        // 字段用 \\x1f(单元分隔)分隔,行用换行分隔;%D 为引用修饰(含 tag)
        String fmt = "%H%x1f%h%x1f%s%x1f%an%x1f%ad%x1f%D";
        String out = sshGitClient.exec(session, StrUtil.format(
                "cd {} && git log origin/{} -n {} --date=format:'%Y-%m-%d %H:%M' --format='{}'",
                quote(repoDir), branch, limit, fmt));
        List<CommitInfo> commits = new ArrayList<>();
        for (String line : out.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            String[] f = line.split("\\u001f", -1);
            if (f.length < 5) continue;
            String tag = f.length > 5 ? extractTags(f[5]) : "";
            commits.add(new CommitInfo(f[0], f[1], f[2], f[3], f[4], tag));
        }
        return commits;
    }

    /** 从 git 的引用修饰(%D)中提取 tag,如 "HEAD -> master, tag: v1.0, origin/master" -> "v1.0" */
    private String extractTags(String decoration) {
        if (StrUtil.isBlank(decoration)) return "";
        List<String> tags = new ArrayList<>();
        for (String ref : decoration.split(",")) {
            String r = ref.trim();
            if (r.startsWith("tag: ")) {
                tags.add(r.substring("tag: ".length()).trim());
            }
        }
        return String.join(", ", tags);
    }


    /**
     * 切到 origin/分支 并对齐到最新(确保分支正确);若指定 commit,则校验其存在且属于该分支后回退到该 commit。
     */
    private void checkout(Session session, String repoDir, CompareRef ref) {
        String branch = ref.getBranch().trim();
        String commit = ref.getCommit() == null ? "" : ref.getCommit().trim();

        // 3.1 确认远程分支存在
        try {
            sshGitClient.exec(session, StrUtil.format(
                    "cd {} && git show-ref --verify --quiet refs/remotes/origin/{}", quote(repoDir), branch));
        } catch (RuntimeException e) {
            throw new RuntimeException("分支不存在: " + branch, e);
        }

        // 3.2 切到分支并对齐到最新(确保当前分支正确)
        sshGitClient.exec(session, StrUtil.format(
                "cd {} && git checkout -B {} origin/{} && git reset --hard origin/{}",
                quote(repoDir), branch, branch, branch));

        if (commit.isEmpty()) {
            return;
        }

        // 3.3 从 log 确认 commit 存在且属于该分支
        ensureCommitOnBranch(session, repoDir, branch, commit);

        // 3.4 回退到对应 commit(detached HEAD)
        sshGitClient.exec(session, StrUtil.format(
                "cd {} && git checkout --detach {}", quote(repoDir), quote(commit)));
    }

    private void ensureCommitOnBranch(Session session, String repoDir, String branch, String commit) {
        // 对象必须存在且为 commit
        String type;
        try {
            type = sshGitClient.exec(session,
                    StrUtil.format("cd {} && git cat-file -t {}", quote(repoDir), quote(commit))).trim();
        } catch (RuntimeException e) {
            throw new RuntimeException("commit不存在: " + commit, e);
        }
        if (!"commit".equals(type)) {
            throw new RuntimeException("提供的不是有效的commit: " + commit + "(type=" + type + ")");
        }
        // commit 必须可从该分支历史到达
        try {
            sshGitClient.exec(session, StrUtil.format(
                    "cd {} && git merge-base --is-ancestor {} origin/{}", quote(repoDir), quote(commit), branch));
        } catch (RuntimeException e) {
            throw new RuntimeException("commit[" + commit + "]不属于分支[" + branch + "]", e);
        }
    }

    private String trimTrailingSlash(String s) {
        return StrUtil.removeSuffix(s.trim(), "/");
    }

    private String trimSlash(String s) {
        return StrUtil.removePrefix(StrUtil.removeSuffix(s.trim(), "/"), "/");
    }

    /** 简单单引号包裹,防止路径含空格/特殊字符 */
    private String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
