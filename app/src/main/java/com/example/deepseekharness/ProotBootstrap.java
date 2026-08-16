package com.example.deepseekharness;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Proot 引导器 — 管理 proot 加载、Ubuntu rootfs 和命令执行。
 *
 * 核心思路：proot 二进制伪装成 libproot.so 放在 jniLibs/arm64-v8a/，
 * Android 安装时自动解压到 nativeLibraryDir（可执行分区），
 * 通过 /system/bin/linker64 启动，绕过 App 私有目录的 noexec 限制。
 */
public class ProotBootstrap {

    // ================= 镜像源 =================
    public static final String[] ROOTFS_URLS = {
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
        "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
        "https://mirrors.huaweicloud.com/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
    };

    public static final String[] NODE_URLS = {
        "https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
        "https://mirrors.huaweicloud.com/nodejs/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
        "https://nodejs.org/dist/v24.19.0/node-v24.19.0-linux-arm64.tar.xz"
    };

    public static final int NODE_MAJOR = 24;
    public static final String NODE_VERSION = "24.19.0";

    private final Context ctx;
    private final File baseDir;
    private final File rootfsDir;
    private final File libDir;
    private final File tmpDir;
    private final String nativeLibDir;
    private final File markerFile;

    public ProotBootstrap(Context c) {
        this.ctx = c.getApplicationContext();
        this.baseDir = new File(ctx.getFilesDir(), "linux");
        this.rootfsDir = new File(baseDir, "ubuntu");
        this.libDir = new File(baseDir, "lib");
        this.tmpDir = new File(baseDir, "tmp");
        this.nativeLibDir = ctx.getApplicationInfo().nativeLibraryDir;
        this.markerFile = new File(baseDir, ".installed");
    }

    public File getRootfsDir() { return rootfsDir; }
    public File getBaseDir() { return baseDir; }

    public boolean isInstalled() {
        // 校验 etc/ 和 root/ 两个关键目录：strip=1 的错误解压不会产生 etc/，
        // 只有 strip=0 的正确解压才会，避免误判已安装
        return markerFile.exists()
                && new File(rootfsDir, "etc").isDirectory()
                && new File(rootfsDir, "root").isDirectory();
    }

    public boolean isNodeInstalled() {
        return new File(rootfsDir, "usr/local/bin/node").exists();
    }

    public boolean isDshInstalled() {
        try {
            String r = execAndRead("command -v dsh 2>/dev/null || echo MISSING");
            return r != null && !r.startsWith("ERROR") && !r.contains("MISSING");
        } catch (Exception e) {
            return false;
        }
    }

    // ================= 文件准备 =================

    /** 确保运行时依赖库就绪 */
    public void ensureRuntimeFiles() {
        baseDir.mkdirs();
        tmpDir.mkdirs();
        libDir.mkdirs();
        // 复制 libtalloc.so -> libtalloc.so.2（proot 的 NEEDED）
        copyExec(findNativeLib("libtalloc.so"), new File(libDir, "libtalloc.so.2"));
        // 复制 libandroidshmem.so
        copyExec(findNativeLib("libandroidshmem.so"), new File(libDir, "libandroid-shmem.so"));
    }

    private File findNativeLib(String name) {
        File direct = new File(nativeLibDir, name);
        if (direct.isFile()) return direct;
        // 扫描父目录下的 ABI 子目录
        File libRoot = new File(nativeLibDir).getParentFile();
        if (libRoot != null && libRoot.isDirectory()) {
            File[] subs = libRoot.listFiles();
            if (subs != null) {
                for (File sub : subs) {
                    if (sub.isDirectory()) {
                        File f = new File(sub, name);
                        if (f.isFile()) return f;
                    }
                }
            }
        }
        return direct;
    }

    private void chmodExec(File f) {
        f.setReadable(true, false);
        f.setExecutable(true, false);
        try {
            android.system.Os.chmod(f.getAbsolutePath(), 0755);
        } catch (Throwable ignored) {}
    }

    private void copyExec(File src, File dst) {
        if (src.isFile() && !dst.exists()) {
            try (InputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            } catch (IOException ignored) {}
            chmodExec(dst);
        }
    }

    // ================= proot 命令执行 =================

    /** 在 rootfs 内执行 bash 命令 */
    public Process execRootfs(String bashCommand) throws IOException {
        String[] argv = {
            prootPath(),
            "--link2symlink", "-L", "--kill-on-exit",
            "-0",
            "--rootfs=" + rootfsDir.getAbsolutePath(),
            "--cwd=/root",
            "-b", "/dev",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/proc/self/fd:/dev/fd",
            "/bin/bash", "-c", bashCommand
        };
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        pb.environment().put("PROOT_TMP_DIR", tmpDir.getAbsolutePath());
        pb.environment().put("PROOT_LOADER", findNativeLib("libprootloader.so").getAbsolutePath());
        pb.environment().put("PROOT_LOADER_32", findNativeLib("libprootloader32.so").getAbsolutePath());
        pb.environment().put("LD_LIBRARY_PATH",
            libDir.getAbsolutePath() + ":" + findNativeLib("libproot.so").getParent());
        pb.environment().put("HOME", "/root");
        pb.environment().put("PATH",
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        pb.environment().put("TMPDIR", "/tmp");
        pb.environment().put("DEBIAN_FRONTEND", "noninteractive");
        return pb.start();
    }

    private String prootPath() {
        return findNativeLib("libproot.so").getAbsolutePath();
    }

    /** 同步执行并返回输出 */
    public String execAndRead(String bashCommand) {
        try {
            Process p = execRootfs(bashCommand);
            String out = readStream(p.getInputStream());
            p.waitFor();
            return out;
        } catch (Throwable e) {
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** 同步执行，退出码非 0 时抛异常 */
    public String execChecked(String bashCommand) throws IOException {
        Process p = execRootfs(bashCommand);
        String out = readStream(p.getInputStream());
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("命令被中断", e);
        }
        if (code != 0) {
            String tail = out.length() > 600 ? out.substring(out.length() - 600) : out;
            throw new IOException("退出码 " + code + ":\n" + tail);
        }
        return out;
    }

    private String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toString("UTF-8");
    }

    // ================= 下载工具 =================

    /** 阻塞读取进程输出（保持长驻进程存活），进程退出时返回最后一段输出 */
    public String drainOutput(Process p) throws IOException {
        InputStream in = p.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int kept = 0;
        final int MAX = 64 * 1024;
        while ((n = in.read(buf)) != -1) {
            if (kept < MAX) {
                int w = Math.min(n, MAX - kept);
                bos.write(buf, 0, w);
                kept += w;
            }
        }
        return bos.toString("UTF-8");
    }

    /** 下载文件到指定路径，带进度回调 */
    public void downloadFile(String urlStr, File dest, ProgressCallback cb) throws IOException {
        dest.getParentFile().mkdirs();
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "DSH-Mobile/1.0");
        int contentLen = conn.getContentLength();
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                if (cb != null && contentLen > 0) {
                    cb.onProgress(total, contentLen);
                }
            }
        }
    }

    public interface ProgressCallback {
        void onProgress(int current, int total);
    }

    /** HEAD 请求测速，返回毫秒，失败返回 -1 */
    public long probeLatency(String url, int timeoutMs) {
        long start = System.currentTimeMillis();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("HEAD");
            int code = conn.getResponseCode();
            conn.disconnect();
            return (code == 200 || code == 206) ? System.currentTimeMillis() - start : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    // ================= 冒烟测试 =================

    public String smokeTest() {
        ensureRuntimeFiles();
        StringBuilder diag = new StringBuilder();
        diag.append("proot: ").append(prootPath()).append("\n");
        try {
            ProcessBuilder pb = new ProcessBuilder(prootPath(), "--version")
                .redirectErrorStream(true);
            pb.environment().put("LD_LIBRARY_PATH",
                libDir.getAbsolutePath() + ":" + findNativeLib("libproot.so").getParent());
            Process p = pb.start();
            String v = readStream(p.getInputStream());
            p.waitFor();
            diag.append("version: ").append(v != null ? v.trim() : "(null)").append("\n");
        } catch (Throwable e) {
            return "FAIL: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        String out = execAndRead("echo PROOT_OK && uname -a");
        diag.append("rootfs: ").append(out != null ? out.trim() : "(null)");
        return diag.toString();
    }
}