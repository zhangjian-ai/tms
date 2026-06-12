package com.seeker.tms.biz.confdiff.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.seeker.tms.biz.confdiff.entities.ConfMachinePO;
import com.seeker.tms.biz.confdiff.enums.AuthType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Vector;

/**
 * 远程机器 SSH 客户端:打开会话、执行命令、SFTP 递归下载。
 * 直接基于 jsch,以支持私钥 byte[] 免落盘加载。
 */
@Slf4j
@Component
public class SshGitClient {

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    /** 命令执行超时(clone/fetch 可能较慢) */
    private static final long COMMAND_TIMEOUT_MS = 300_000;

    /**
     * 打开 SSH 会话,支持 password 与 private_key 两种鉴权
     */
    public Session openSession(ConfMachinePO machine) {
        try {
            JSch jsch = new JSch();
            if (machine.getAuthType() == AuthType.PRIVATE_KEY) {
                if (StrUtil.isBlank(machine.getPrivateKey())) {
                    throw new IllegalArgumentException("私钥鉴权方式下私钥内容不能为空");
                }
                byte[] prvKey = machine.getPrivateKey().getBytes(StandardCharsets.UTF_8);
                byte[] passphrase = StrUtil.isBlank(machine.getPassphrase())
                        ? null : machine.getPassphrase().getBytes(StandardCharsets.UTF_8);
                jsch.addIdentity(machine.getName(), prvKey, null, passphrase);
            }

            Session session = jsch.getSession(machine.getUsername(), machine.getHost(),
                    machine.getPort() == null ? 22 : machine.getPort());

            if (machine.getAuthType() == AuthType.PASSWORD) {
                if (StrUtil.isBlank(machine.getPassword())) {
                    throw new IllegalArgumentException("密码鉴权方式下密码不能为空");
                }
                session.setPassword(machine.getPassword());
            }

            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            session.connect(CONNECT_TIMEOUT_MS);
            return session;
        } catch (Exception e) {
            throw new RuntimeException("SSH连接失败[" + machine.getHost() + ":" + machine.getPort() + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 执行远程命令,非0退出码抛异常(携带 stderr)。返回 stdout。使用默认超时。
     */
    public String exec(Session session, String command) {
        return exec(session, command, COMMAND_TIMEOUT_MS);
    }

    /**
     * 执行远程命令,指定超时(毫秒)。clone/fetch 等耗时操作可传入更长超时。
     */
    public String exec(Session session, String command, long timeoutMs) {
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            channel.setErrStream(err);
            InputStream in = channel.getInputStream();
            channel.connect();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                while (in.available() > 0) {
                    int n = in.read(buf, 0, buf.length);
                    if (n < 0) break;
                    out.write(buf, 0, n);
                }
                if (channel.isClosed()) {
                    if (in.available() > 0) continue;
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new RuntimeException("命令执行超时: " + command);
                }
                sleep();
            }

            int code = channel.getExitStatus();
            String stdout = out.toString(CharsetUtil.UTF_8);
            if (code != 0) {
                String stderr = err.toString(StandardCharsets.UTF_8);
                throw new RuntimeException("远程命令失败(exit=" + code + "): " + command
                        + " | stderr: " + stderr + " | stdout: " + stdout);
            }
            return stdout;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("远程命令执行异常: " + command + " | " + e.getMessage(), e);
        } finally {
            if (channel != null) channel.disconnect();
        }
    }

    /**
     * SFTP 递归下载远程目录到本地。远程路径不存在则抛异常。
     */
    public void downloadDir(Session session, String remoteDir, File localDir) {
        ChannelSftp sftp = null;
        try {
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(CONNECT_TIMEOUT_MS);
            downloadRecursive(sftp, remoteDir, localDir);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("SFTP下载失败[" + remoteDir + "]: " + e.getMessage(), e);
        } finally {
            if (sftp != null) sftp.disconnect();
        }
    }

    @SuppressWarnings("unchecked")
    private void downloadRecursive(ChannelSftp sftp, String remoteDir, File localDir) throws Exception {
        FileUtil.mkdir(localDir);
        Vector<ChannelSftp.LsEntry> entries = sftp.ls(remoteDir);
        for (ChannelSftp.LsEntry entry : entries) {
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name)) continue;
            String remotePath = remoteDir + "/" + name;
            File localChild = new File(localDir, name);
            if (entry.getAttrs().isDir()) {
                downloadRecursive(sftp, remotePath, localChild);
            } else if (entry.getAttrs().isLink()) {
                // 跟随符号链接:解析真实路径后按文件或目录处理
                String real = sftp.realpath(remotePath);
                if (sftp.stat(real).isDir()) {
                    downloadRecursive(sftp, real, localChild);
                } else {
                    sftp.get(real, localChild.getAbsolutePath());
                }
            } else {
                sftp.get(remotePath, localChild.getAbsolutePath());
            }
        }
    }

    public void close(Session session) {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    private void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
