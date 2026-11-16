package com.tvstation.remote;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.view.View;
import android.view.Window;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String LOCAL_URL = "http://127.0.0.1:8098/";
    private static final String HEALTH_URL = "http://127.0.0.1:8098/api/health";
    private static final String TERMUX_PKG = "com.termux";
    private static final String BOOT_PKG = "com.termux.boot";

    private WebView web;
    private View setupPanel;
    private View errorPanel;
    private TextView setupStatus;
    private Button btnInstallTermux;
    private Button btnInstallBoot;
    private Button btnOpenTermux;
    private Button btnCopy;
    private Button btnRecheck;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private boolean termuxInstalled = false;
    private boolean bootInstalled = false;
    private boolean backendOk = false;
    private boolean backendChecking = false;
    private boolean webLoaded = false;
    private boolean hasPaused = false;
    private boolean loadFailed = false;
    private int checkGen = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(Color.parseColor("#fb7299"));
        window.setNavigationBarColor(Color.parseColor("#f4f6f9"));

        setContentView(R.layout.activity_main);

        web = findViewById(R.id.web);
        setupPanel = findViewById(R.id.setupPanel);
        errorPanel = findViewById(R.id.errorPanel);
        setupStatus = findViewById(R.id.setupStatus);
        btnInstallTermux = findViewById(R.id.btnInstallTermux);
        btnInstallBoot = findViewById(R.id.btnInstallBoot);
        btnOpenTermux = findViewById(R.id.btnOpenTermux);
        btnCopy = findViewById(R.id.btnCopy);
        btnRecheck = findViewById(R.id.btnRecheck);

        setupWebView();

        btnInstallTermux.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl("https://f-droid.org/packages/com.termux/");
            }
        });
        btnInstallBoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl("https://f-droid.org/packages/com.termux.boot/");
            }
        });
        btnOpenTermux.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTermux();
            }
        });
        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyCommands();
            }
        });
        btnRecheck.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runPreflight();
            }
        });

        findViewById(R.id.btnRetry).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (webLoaded) {
                    loadFailed = false;
                    errorPanel.setVisibility(View.GONE);
                    web.setVisibility(View.VISIBLE);
                    web.loadUrl(LOCAL_URL + "?from=app");
                } else {
                    runPreflight();
                }
            }
        });
        findViewById(R.id.btnTermux).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTermux();
            }
        });

        runPreflight();
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                loadFailed = false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost();
                if ("127.0.0.1".equals(host) || "localhost".equals(host)) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                    // 没有浏览器就算了
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!loadFailed) {
                    errorPanel.setVisibility(View.GONE);
                    web.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    loadFailed = true;
                    showError();
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                if (request.isForMainFrame() && errorResponse.getStatusCode() >= 500) {
                    loadFailed = true;
                    showError();
                }
            }
        });
    }

    private void runPreflight() {
        webLoaded = false;
        setupPanel.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);
        web.setVisibility(View.GONE);

        termuxInstalled = isPkgInstalled(TERMUX_PKG);
        bootInstalled = isPkgInstalled(BOOT_PKG);
        backendChecking = true;
        backendOk = false;

        updateStatus();
        updateButtons();
        checkBackend();
    }

    private void updateStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("√ Android ").append(Build.VERSION.RELEASE)
          .append("（API ").append(Build.VERSION.SDK_INT).append("）\n");

        String abi = "";
        if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
            abi = Build.SUPPORTED_ABIS[0];
        }
        sb.append("√ CPU 架构 ").append(abi.isEmpty() ? "未知" : abi).append("\n");

        long free = freeBytes();
        sb.append(free > 300L * 1024 * 1024 ? "√" : "!")
          .append(" 存储可用 ").append(formatSize(free)).append("\n");

        sb.append(termuxInstalled ? "√" : "×")
          .append(" Termux ").append(termuxInstalled ? "已安装" : "未安装").append("\n");
        sb.append(bootInstalled ? "√" : "×")
          .append(" Termux:Boot ").append(bootInstalled ? "已安装" : "未安装").append("\n");

        if (backendChecking) {
            sb.append("... 后端检测中");
        } else {
            sb.append(backendOk ? "√ 后端已连接" : "× 后端未启动");
        }
        setupStatus.setText(sb.toString());
    }

    private void updateButtons() {
        btnInstallTermux.setVisibility(termuxInstalled ? View.GONE : View.VISIBLE);
        btnInstallBoot.setVisibility(bootInstalled ? View.GONE : View.VISIBLE);
        btnOpenTermux.setVisibility(termuxInstalled ? View.VISIBLE : View.GONE);
        btnCopy.setVisibility(termuxInstalled ? View.VISIBLE : View.GONE);
        btnRecheck.setEnabled(!backendChecking);
    }

    private void checkBackend() {
        final int gen = ++checkGen;
        io.execute(new Runnable() {
            @Override
            public void run() {
                final boolean ok = probeHealth();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != checkGen) return;
                        backendChecking = false;
                        backendOk = ok;
                        updateStatus();
                        updateButtons();
                        if (termuxInstalled && bootInstalled && ok) {
                            enterApp();
                        }
                    }
                });
            }
        });
    }

    private boolean probeHealth() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(HEALTH_URL).openConnection();
            conn.setConnectTimeout(1200);
            conn.setReadTimeout(1200);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            int code = conn.getResponseCode();
            if (code != 200) return false;
            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            r.close();
            return sb.toString().contains("\"ok\"");
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void enterApp() {
        if (webLoaded) return;
        webLoaded = true;
        setupPanel.setVisibility(View.GONE);
        errorPanel.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        web.loadUrl(LOCAL_URL + "?from=app");
    }

    private void showError() {
        web.setVisibility(View.GONE);
        setupPanel.setVisibility(View.GONE);
        errorPanel.setVisibility(View.VISIBLE);
    }

    private boolean isPkgInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private long freeBytes() {
        try {
            StatFs fs = new StatFs(getFilesDir().getAbsolutePath());
            return fs.getAvailableBytes();
        } catch (Exception e) {
            return 0;
        }
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) return "未知";
        double gb = bytes / 1024.0 / 1024.0 / 1024.0;
        if (gb >= 1) return String.format(Locale.US, "%.1f GB", gb);
        return String.format(Locale.US, "%.0f MB", bytes / 1024.0 / 1024.0);
    }

    private void openTermux() {
        Intent i = getPackageManager().getLaunchIntentForPackage(TERMUX_PKG);
        if (i == null) {
            Toast.makeText(this, R.string.termux_missing, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivity(i);
        } catch (Exception ignored) {
            // ignore
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "没有找到浏览器，请手动打开 " + url,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void copyCommands() {
        String cmds = "pkg update -y && pkg upgrade -y\n"
                + "pkg install -y python android-tools termux-services iproute2 curl unzip\n"
                + "cd ~/tvstation\n"
                + "bash install-termux.sh\n"
                + "./tvctl status\n";
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("tvstation", cmds));
            Toast.makeText(this, R.string.cmd_copied, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (webLoaded && web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        hasPaused = true;
        if (webLoaded && web != null) {
            web.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasPaused) {
            hasPaused = false;
            if (!webLoaded) {
                runPreflight();
            }
        }
        if (webLoaded && web != null) {
            web.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
