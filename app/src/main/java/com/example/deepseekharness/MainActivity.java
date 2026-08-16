package com.example.deepseekharness;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {

    private static final int DSH_PORT = 3080;
    private static final String DSH_URL = "http://127.0.0.1:" + DSH_PORT;

    private WebView webView;
    private View splash;
    private TextView tvTitle;
    private TextView tvStatus;
    private LinearLayout setupPanel;
    private Button btnInstall;
    private Button btnStart;
    private Button btnStop;
    private Button btnRemote;
    private ProgressBar progressBar;

    private ProotBootstrap proot;
    private Handler handler = new Handler(Looper.getMainLooper());
    private volatile Process dshProcess;

    private enum State { INIT, NEED_ROOTFS, NEED_SETUP, READY, RUNNING, ERROR }
    private State state = State.INIT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        proot = new ProotBootstrap(this);

        webView = findViewById(R.id.webview);
        splash = findViewById(R.id.splash);
        tvTitle = findViewById(R.id.tv_title);
        tvStatus = findViewById(R.id.tv_status);
        setupPanel = findViewById(R.id.setup_panel);
        btnInstall = findViewById(R.id.btn_install);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnRemote = findViewById(R.id.btn_remote);
        progressBar = findViewById(R.id.progress_bar);

        setupWebView();
        setupButtons();

        // 检查环境
        checkEnvironment();
    }

    private void setupWebView() {
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                view.loadUrl(request.getUrl().toString());
                return true;
            }
        });
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setUserAgentString(
            webView.getSettings().getUserAgentString() + " DSH-Mobile/1.0");
    }

    private void setupButtons() {
        btnInstall.setOnClickListener(v -> startInstall());
        btnStart.setOnClickListener(v -> startDsh());
        btnStop.setOnClickListener(v -> stopDsh());
        btnRemote.setOnClickListener(v -> showRemoteDialog());
    }

    // ================= 环境检查 =================

    private void checkEnvironment() {
        setState(State.INIT);
        updateStatus("检查运行环境...");

        new Thread(() -> {
            proot.ensureRuntimeFiles();

            if (!proot.isInstalled()) {
                setState(State.NEED_ROOTFS);
                updateStatus("需要安装 Ubuntu 环境 (约 30MB)");
                return;
            }

            if (!proot.isNodeInstalled()) {
                setState(State.NEED_SETUP);
                updateStatus("需要安装 Node.js + DSH");
                return;
            }

            if (!proot.isDshInstalled()) {
                setState(State.NEED_SETUP);
                updateStatus("需要安装 @deepseek-ai/dsh");
                return;
            }

            setState(State.READY);
            updateStatus("环境就绪，可以启动");
        }).start();
    }

    // ================= 安装流程 =================

    private void startInstall() {
        btnInstall.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        setState(State.INIT);

        new Thread(() -> {
            try {
                // 步骤 0: 冒烟测试 - 验证 proot 能正常执行
                updateStatus("验证 proot 环境...");
                String smoke = proot.smokeTest();
                if (smoke != null && smoke.startsWith("FAIL")) {
                    throw new IOException("proot 无法执行: " + smoke);
                }

                // 步骤 1: 下载并解压 Ubuntu rootfs
                if (!proot.isInstalled()) {
                    // 清理旧的损坏/不完整 rootfs（如有）
                    if (proot.getRootfsDir().exists()) {
                        updateStatus("清理旧的 rootfs...");
                        deleteRecursively(proot.getRootfsDir());
                        new File(proot.getBaseDir(), ".installed").delete();
                    }

                    updateStatus("正在下载 Ubuntu rootfs...");
                    String rootfsUrl = pickFastestUrl(ProotBootstrap.ROOTFS_URLS);
                    File tmpFile = new File(proot.getBaseDir(), "rootfs.tar.gz");
                    proot.downloadFile(rootfsUrl, tmpFile, (cur, total) -> {
                        int pct = (int) ((long) cur * 100 / total);
                        updateProgress("下载 rootfs", pct);
                    });

                    updateStatus("正在解压 rootfs...");
                    // 重要：ubuntu-base tarball 顶层没有包装目录，必须 strip=0
                    TarGzipExtractor.extract(tmpFile, proot.getRootfsDir(), 0);
                    tmpFile.delete();

                    // 校验解压结果
                    if (!new File(proot.getRootfsDir(), "etc").isDirectory()
                            || !new File(proot.getRootfsDir(), "root").isDirectory()) {
                        throw new IOException("rootfs 解压不完整，缺少 etc/ 或 root/ 目录");
                    }

                    // 标记安装完成
                    new File(proot.getBaseDir(), ".installed").createNewFile();
                }

                // 步骤 2: 运行 install.sh 安装 Node.js + DSH
                if (!proot.isNodeInstalled() || !proot.isDshInstalled()) {
                    updateStatus("安装 Node.js + DSH (约 5-10 分钟)...");
                    progressBar.setIndeterminate(true);

                    // 复制 install.sh 到 rootfs
                    try (InputStream is = getAssets().open("install.sh");
                         FileOutputStream os = new FileOutputStream(
                             new File(proot.getRootfsDir(), "root/install.sh"))) {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
                    }
                    new File(proot.getRootfsDir(), "root/install.sh").setExecutable(true);

                    // 在 proot 中执行 install.sh
                    Process p = proot.execRootfs("bash /root/install.sh");
                    String output = proot.drainOutput(p);
                    int code = p.waitFor();
                    if (code != 0) {
                        throw new IOException("安装失败，退出码 " + code + "\n" + output);
                    }
                }

                handler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    progressBar.setIndeterminate(false);
                    btnInstall.setEnabled(true);
                    setState(State.READY);
                    updateStatus("安装完成，点击启动");
                });

            } catch (Exception e) {
                handler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    progressBar.setIndeterminate(false);
                    btnInstall.setEnabled(true);
                    setState(State.ERROR);
                    String detail = e.getMessage();
                    if (detail != null && detail.length() > 800) {
                        detail = detail.substring(detail.length() - 800);
                    }
                    updateStatus("安装失败: " + detail);
                    android.util.Log.e("DSH", "安装失败", e);
                });
            }
        }).start();
    }

    // ================= 启动/停止 DSH =================

    private void startDsh() {
        setState(State.INIT);
        updateStatus("正在启动 DSH...");
        btnStart.setEnabled(false);

        new Thread(() -> {
            try {
                // 在 proot 中启动 dsh web
                dshProcess = proot.execRootfs(
                    "cd /root && PORT=" + DSH_PORT + " dsh web 2>&1");

                // 等待服务就绪
                int retries = 60;
                while (retries-- > 0) {
                    if (isServerReady()) {
                        handler.post(() -> {
                            setState(State.RUNNING);
                            splash.setVisibility(View.GONE);
                            webView.setVisibility(View.VISIBLE);
                            webView.loadUrl(DSH_URL);
                            btnStart.setEnabled(true);

                            // 启动前台服务保活
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(
                                    new Intent(this, HarnessService.class)
                                        .setAction(HarnessService.ACTION_START));
                            } else {
                                startService(new Intent(this, HarnessService.class)
                                    .setAction(HarnessService.ACTION_START));
                            }
                        });
                        return;
                    }
                    Thread.sleep(1000);
                }
                throw new IOException("DSH 服务未在 60 秒内启动");
            } catch (Exception e) {
                handler.post(() -> {
                    setState(State.ERROR);
                    updateStatus("启动失败: " + e.getMessage());
                    btnStart.setEnabled(true);
                });
            }
        }).start();
    }

    private void stopDsh() {
        if (dshProcess != null && dshProcess.isAlive()) {
            dshProcess.destroy();
            dshProcess = null;
        }
        // 停止前台服务
        stopService(new Intent(this, HarnessService.class)
            .setAction(HarnessService.ACTION_STOP));
        webView.setVisibility(View.GONE);
        splash.setVisibility(View.VISIBLE);
        setState(State.READY);
        updateStatus("已停止");
    }

    private boolean isServerReady() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(DSH_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1000);
            conn.setRequestMethod("GET");
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ================= 远程连接 =================

    private void showRemoteDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("远程连接模式");

        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("服务器地址 (例如: 192.168.1.100:3080)");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        builder.setView(input);

        builder.setPositiveButton("连接", (d, w) -> {
            String address = input.getText().toString().trim();
            if (address.isEmpty()) {
                Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!address.startsWith("http")) {
                address = "http://" + address;
            }
            connectToRemote(address);
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void connectToRemote(String url) {
        updateStatus("正在连接: " + url);
        splash.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
        setState(State.RUNNING);
    }

    // ================= 工具方法 =================

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    private String pickFastestUrl(String[] urls) {
        long bestLat = Long.MAX_VALUE;
        String bestUrl = urls[0];
        for (String url : urls) {
            long lat = proot.probeLatency(url, 5000);
            if (lat >= 0 && lat < bestLat) {
                bestLat = lat;
                bestUrl = url;
            }
        }
        return bestUrl;
    }

    // ================= UI 状态 =================

    private void setState(State s) {
        this.state = s;
        handler.post(this::updateUi);
    }

    private void updateUi() {
        boolean installing = (state == State.INIT && progressBar.getVisibility() == View.VISIBLE);

        btnInstall.setVisibility(
            (state == State.NEED_ROOTFS || state == State.NEED_SETUP || state == State.ERROR)
                ? View.VISIBLE : View.GONE);
        btnStart.setVisibility(
            (state == State.READY) ? View.VISIBLE : View.GONE);
        btnStop.setVisibility(
            (state == State.RUNNING) ? View.VISIBLE : View.GONE);
        btnRemote.setVisibility(View.VISIBLE);
        setupPanel.setVisibility(
            (state == State.RUNNING) ? View.GONE : View.VISIBLE);
    }

    private void updateStatus(final String text) {
        handler.post(() -> {
            if (tvStatus != null) tvStatus.setText(text);
        });
    }

    private void updateProgress(final String stage, final int percent) {
        handler.post(() -> {
            if (tvStatus != null) tvStatus.setText(stage + " " + percent + "%");
            if (progressBar != null) {
                progressBar.setIndeterminate(false);
                progressBar.setProgress(percent);
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        // Activity 销毁时停止服务
        stopService(new Intent(this, HarnessService.class)
            .setAction(HarnessService.ACTION_STOP));
        super.onDestroy();
    }
}