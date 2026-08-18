package com.deepseekharness.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.net.HttpURLConnection;
import java.net.URL;

/** 启动模块：启动/重启/停止 Web UI；启动后自动检测就绪并弹出预览 */
public class LaunchFragment extends Fragment {

    private HarnessController c;
    private WebView webView;
    private FrameLayout previewContainer;
    private org.mozilla.geckoview.GeckoView geckoView;
    private org.mozilla.geckoview.GeckoSession geckoSession;
    private org.mozilla.geckoview.GeckoRuntime geckoRuntime;
    private TextView statusText;
    private TextView lanAddrText;
    private Button startBtn, restartBtn, stopBtn;
    private LinearLayout controls;
    private boolean fullscreen = false;
    private boolean polling = false;
    private boolean previewBusy = false;
    // Web 主题同步：网页(DSH 设置)切深/浅色时，状态栏/导航栏/窗口背景跟随
    private final Handler themeHandler = new Handler(Looper.getMainLooper());
    private final Runnable themePoller = new Runnable() {
        @Override
        public void run() {
            applyWebTheme();
            themeHandler.postDelayed(this, 1500);
        }
    };

    /** 读取网页当前主题(DSH ui-theme)，同步系统栏。
     *  JS 侧把 --dsw-alias-bg-base 解析成 #rrggbb（getPropertyValue 拿到的只是未展开的
     *  var() 引用，Android 的 Color.parseColor 无法解析）；dark 取 html 内联 colorScheme，
     *  未明确设置时跟随 prefers-color-scheme，作为解析失败时的兜底。 */
    private void applyWebTheme() {
        WebView wv = webView;
        if (wv == null || getActivity() == null) return;
        try {
            wv.evaluateJavascript(
                    "(function(){" +
                    "var scheme=document.documentElement.style.colorScheme||'';" +
                    "var sysDark=typeof matchMedia!=='undefined'&&matchMedia('(prefers-color-scheme: dark)').matches;" +
                    "var dark=scheme==='dark'||(scheme!=='light'&&!!sysDark);" +
                    "var bg='';" +
                    "try{var p=document.createElement('div');p.style.backgroundColor='var(--dsw-alias-bg-base)';" +
                    "document.documentElement.appendChild(p);" +
                    "var c=getComputedStyle(p).backgroundColor;p.remove();" +
                    "var m=c.match(/(\\d+)[^\\d]*(\\d+)[^\\d]*(\\d+)/);" +
                    "if(m&&!c.match(/rgba?\\(0,\\s*0,\\s*0,\\s*0\\)/)){" +
                    "bg='#'+[+m[1],+m[2],+m[3]].map(function(v){return('0'+v.toString(16)).slice(-2)}).join('');}" +
                    "}catch(e){}" +
                    "return JSON.stringify({dark:dark,bg:bg});})()",
                    value -> {
                        if (getActivity() == null || value == null || value.length() < 3) return;
                        String v = value.trim();
                        if (v.startsWith("\"") || v.startsWith("\'")) return; // 页面未就绪时返回字符串
                        try {
                            org.json.JSONObject j = new org.json.JSONObject(v);
                            boolean dark = j.optBoolean("dark", false);
                            String bg = j.optString("bg", "");
                            applySystemBarTheme(dark, bg);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    /** 应用主题到系统栏：状态栏/导航栏背景色 + 图标深浅。
     *  背景色能解析时按背景亮度决定图标颜色（页面主题切换后 style.colorScheme 可能过期），
     *  解析失败才退回 colorScheme 判断。 */
    private void applySystemBarTheme(boolean dark, String bg) {
        android.app.Activity act = getActivity();
        if (act == null) return;
        int bgColor;
        if (bg != null && bg.length() == 7) {
            try {
                bgColor = android.graphics.Color.parseColor(bg);
                dark = !isLightColor(bgColor);
            } catch (Exception e) {
                bgColor = dark ? 0xFF0F1115 : 0xFFFFFFFF;
            }
        } else {
            bgColor = dark ? 0xFF0F1115 : 0xFFFFFFFF;
        }
        act.getWindow().setStatusBarColor(bgColor);
        act.getWindow().setNavigationBarColor(bgColor);
        setSystemBarsAppearance(dark);
    }

    /** 按亮度判断颜色是否属于浅色（用于决定状态栏图标用深色还是浅色） */
    private boolean isLightColor(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (299 * r + 587 * g + 114 * b) / 1000 >= 128;
    }

    /** 设置状态栏/导航栏图标深浅：
     *  Android 11+ 用 WindowInsetsController（setSystemUiVisibility 的 LIGHT_* 标志已废弃、
     *  在部分系统上不生效）；旧版本用传统标志。 */
    private void setSystemBarsAppearance(boolean dark) {
        android.app.Activity act = getActivity();
        if (act == null) return;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowInsetsController c = act.getWindow().getInsetsController();
            if (c != null) {
                int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(dark ? 0 : light, light);
            }
        } else {
            View decor = act.getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (dark) {
                flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    /** 退出预览后按 App 自身主题恢复系统栏（透明背景 + 图标深浅跟随主题），
     *  避免残留网页主题导致日间白字白底 / 夜间深字深底。 */
    private void applyAppThemeSystemBars() {
        android.app.Activity act = getActivity();
        if (act == null) return;
        act.getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        act.getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        setSystemBarsAppearance(dark);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final OnBackPressedCallback backCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            exitFullscreen();
        }
    };

    /** 根据设置初始化预览内核：系统 WebView 或内置 GeckoView；电脑模式设置桌面 UA */
    @SuppressLint("SetJavaScriptEnabled")
    private void initPreview() {
        android.content.SharedPreferences prefs = requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE);
        boolean useGecko = prefs.getBoolean("gecko_core", false);
        boolean desktopMode = prefs.getBoolean("desktop_mode", false);
        previewContainer.removeAllViews();
        if (useGecko) {
            try {
                webView = null;
                geckoView = new org.mozilla.geckoview.GeckoView(requireContext());
                previewContainer.addView(geckoView,
                        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                if (geckoRuntime == null) {
                    geckoRuntime = org.mozilla.geckoview.GeckoRuntime.create(requireContext());
                }
                geckoSession = new org.mozilla.geckoview.GeckoSession();
                geckoSession.open(geckoRuntime);
                geckoView.setSession(geckoSession);
            } catch (Throwable e) {
                // GeckoView 初始化失败（如 so 加载异常）回退系统 WebView
                geckoView = null;
                geckoSession = null;
                webView = new WebView(requireContext());
                setupWebView(webView, desktopMode);
                previewContainer.addView(webView,
                        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        } else {
            geckoView = null;
            geckoSession = null;
            webView = new WebView(requireContext());
            setupWebView(webView, desktopMode);
            previewContainer.addView(webView,
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private void setupWebView(WebView wv, boolean desktopMode) {
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        if (desktopMode) {
            wv.getSettings().setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
        }
        // 键盘弹出时自动调整布局（配合 manifest 的 adjustResize）。
        // 注意：adjustResize 与 adjustPan 互斥，manifest 只保留 adjustResize，
        // 这里再在运行时强制一次（防止其他代码覆盖窗口模式）。
        android.app.Activity act = getActivity();
        if (act != null) {
            try {
                act.getWindow().setSoftInputMode(
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            } catch (Throwable ignored) {
            }
        }
        wv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wv.setWebViewClient(new WebViewClient());
        // 键盘弹出时，通过 WindowInsets 调整 WebView 底部留白，确保输入框可见。
        // 先请求 insets 分发（嵌套视图默认不一定会收到）。
        wv.requestApplyInsets();
        wv.setOnApplyWindowInsetsListener((v, insets) -> {
            int ime = 0;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                // API 30+：键盘/IME 的 inset 在 ime() 中
                ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
            } else {
                // API 26-29：用 systemWindowInsetBottom 近似（含键盘）
                ime = insets.getSystemWindowInsetBottom();
            }
            if (ime > 0) {
                v.setPadding(0, 0, 0, ime);
            } else {
                v.setPadding(0, 0, 0, 0);
            }
            // 直接返回原始 insets，不消费：剩余 insets 继续在视图树中传播。
            // （部分 compileSdk/AGP 组合下 WindowInsets.consume(int) 不可用，
            //   且消费并非必需——setPadding 已把 IME 高度让给页面。）
            return insets;
        });
        // 兜底：adjustResize 真正生效时窗口会缩小、WebView 高度会变化，
        // 此时页面视口已自然缩小，把手动 padding 清零，避免与新视口叠加。
        wv.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot && v.getPaddingBottom() > 0) {
                v.setPadding(0, 0, 0, 0);
            }
        });
    }

    /** 加载预览 URL（按当前内核分发） */
    private void loadPreview(String url) {
        if (webView != null) {
            webView.loadUrl(url);
        } else if (geckoSession != null) {
            geckoSession.load(new org.mozilla.geckoview.GeckoSession.Loader().uri(url));
        }
    }

    /** 局域网访问地址显示（lan_mode 开启且检测到 IP 时显示，点击复制） */
    private void updateLanAddr() {
        boolean lan = requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("lan_mode", false);
        if (!lan) {
            lanAddrText.setVisibility(View.GONE);
            return;
        }
        String ip = HarnessController.getLanAddress();
        if (ip == null) {
            lanAddrText.setVisibility(View.GONE);
            return;
        }
        String addr = "http://" + ip + ":" + LanProxyService.LAN_PORT + "   （App桥，局域网设备可访问）";
        lanAddrText.setText(addr);
        lanAddrText.setVisibility(View.VISIBLE);
        lanAddrText.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("lan", "http://" + ip + ":" + LanProxyService.LAN_PORT));
            Toast.makeText(requireContext(), "局域网地址已复制", Toast.LENGTH_SHORT).show();
        });
    }

    private final HarnessController.StateListener stateListener = this::refreshFromState;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_launch, container, false);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        c = HarnessController.get(requireContext());
        previewContainer = view.findViewById(R.id.previewContainer);
        statusText = view.findViewById(R.id.launch_status);
        lanAddrText = view.findViewById(R.id.lan_addr);
        startBtn = view.findViewById(R.id.launch_start);
        restartBtn = view.findViewById(R.id.launch_open);
        stopBtn = view.findViewById(R.id.launch_stop);
        controls = view.findViewById(R.id.launch_controls);

        initPreview();
        updateLanAddr();
        // 进入本页时确保系统栏跟随 App 当前主题（清除上一次预览可能残留的网页主题设置）
        applyAppThemeSystemBars();
        // 刷新看门狗命令文件（覆盖历史坏命令，避免旧 watchdog 用空端口 restart 反复失败）
        try {
            c.ensureWatchdogFiles();
        } catch (Throwable ignored) {
        }

        c.addStateListener(stateListener);
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backCallback);

        startBtn.setOnClickListener(v -> {
            if (!c.isHarnessInstalled()) {
                Toast.makeText(requireContext(), "请先在「安装」模块完成安装", Toast.LENGTH_LONG).show();
                return;
            }
            // 通过前台服务启动：强保活 + 后台运行（切走不杀）
            Intent i = new Intent(requireContext(), HarnessService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(i);
            } else {
                requireContext().startService(i);
            }
            statusText.setText("正在启动 Web UI，检测到就绪后自动打开预览…");
            pollWebReady();
        });

        restartBtn.setOnClickListener(v -> {
            if (!c.isHarnessInstalled()) {
                Toast.makeText(requireContext(), "请先完成安装", Toast.LENGTH_LONG).show();
                return;
            }
            exitFullscreen();
            statusText.setText("正在重启 Web UI…");
            Intent stop = new Intent(requireContext(), HarnessService.class)
                    .setAction(HarnessService.ACTION_STOP);
            requireContext().startService(stop);
            // 稍等进程退出，再重新拉起
            mainHandler.postDelayed(() -> {
                Intent i = new Intent(requireContext(), HarnessService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    requireContext().startForegroundService(i);
                } else {
                    requireContext().startService(i);
                }
                statusText.setText("正在重启 Web UI，检测到就绪后自动打开预览…");
                pollWebReady();
            }, 1500);
        });

        stopBtn.setOnClickListener(v -> {
            Intent i = new Intent(requireContext(), HarnessService.class)
                    .setAction(HarnessService.ACTION_STOP);
            requireContext().startService(i);
            exitFullscreen();
            statusText.setText("已发送停止命令");
        });

        // 切模块回来：如果 Web 还在跑，自动恢复全屏预览
        if (c.isWebRunning()) {
            webView.post(this::openPreview);
        } else {
            statusText.setText("提示：先到「安装」页完成安装，再回到这里启动。");
        }
    }

    /** 轮询检测 WebUI 就绪（HTTP 200），就绪后自动打开预览 */
    private void pollWebReady() {
        if (polling) return;
        polling = true;
        final String url = "http://127.0.0.1:" + c.getPort() + "/";
        new Thread(() -> {
            boolean ok = false;
            for (int i = 0; i < 180; i++) { // 最多约 6 分钟（首次 RC6 启动较慢）
                if (!c.isWebRunning() && !ok) {
                    // 服务进程都没了就别等了（除非刚启动拉起中），放宽到 80 秒后才判死
                    if (i > 40) break;
                }
                if (httpOk(url)) {
                    ok = true;
                    break;
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    break;
                }
            }
            polling = false;
            if (ok && isAdded()) {
                mainHandler.post(this::openPreview);
            } else if (isAdded()) {
                mainHandler.post(() ->
                        statusText.setText("等待 Web UI 就绪超时，可点「重启」再试，或检查日志"));
            }
        }).start();
    }

    private boolean httpOk(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private void openPreview() {
        // 防重入：openPreview → loadPreview → loadUrl 反复触发（多入口 post 叠加）会递归爆栈
        if (previewBusy) return;
        previewBusy = true;
        try {
            String url = "http://127.0.0.1:" + c.getPort() + "/";
            loadPreview(url);
            enterFullscreen();
        } catch (Throwable t) {
            // 预览闪避：内核异常不拖垮 App
            try {
                statusText.setText("预览加载异常，可点「重启」再试");
            } catch (Throwable ignored) {
            }
        } finally {
            previewBusy = false;
        }
    }

    private void enterFullscreen() {
        fullscreen = true;
        backCallback.setEnabled(true);
        controls.setVisibility(View.GONE);
        statusText.setVisibility(View.GONE);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setBottomNavVisible(false);
        }
        android.app.Activity act = getActivity();
        if (act == null) return;
        View decor = act.getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Android 11+：使用 WindowInsetsController 隐藏导航栏，但保留状态栏
            // （避免状态栏变黑/被遮挡）。
            android.view.WindowInsetsController c = act.getWindow().getInsetsController();
            if (c != null) {
                c.hide(android.view.WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            // Android 10-：隐藏导航栏但保留状态栏；保留原有图标深浅标志，
            // 避免 setSystemUiVisibility 覆盖掉 LIGHT_STATUS_BAR 造成日间白字白底
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | (decor.getSystemUiVisibility() & (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)));
        }
        // 启动 Web 主题同步（状态栏/导航栏跟随网页深浅色）
        applyWebTheme();
        themeHandler.removeCallbacks(themePoller);
        themeHandler.postDelayed(themePoller, 1500);
    }

    private void exitFullscreen() {
        fullscreen = false;
        backCallback.setEnabled(false);
        controls.setVisibility(View.VISIBLE);
        statusText.setVisibility(View.VISIBLE);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setBottomNavVisible(true);
        }
        android.app.Activity act = getActivity();
        if (act == null) return;
        View decor = act.getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowInsetsController c = act.getWindow().getInsetsController();
            if (c != null) {
                c.show(android.view.WindowInsets.Type.navigationBars());
            }
        } else {
            // 恢复系统 UI 可见；图标深浅交给 applyAppThemeSystemBars() 按主题重设
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
        // 系统栏恢复 App 自身主题（透明背景 + 图标深浅跟随主题），
        // 避免残留网页主题设置（如日间白底浅色图标）导致状态栏看不清
        applyAppThemeSystemBars();
        // 停止 Web 主题轮询（退出全屏回到 App 原生界面）
        themeHandler.removeCallbacks(themePoller);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        themeHandler.removeCallbacks(themePoller);
        if (c != null) c.removeStateListener(stateListener);
        // 退出 Fragment 时恢复底部导航
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setBottomNavVisible(true);
        }
    }

    private void refreshFromState() {
        if (!isAdded()) return;
        if (c.getError() != null && !c.getError().isEmpty()) {
            statusText.setText(c.getError());
        } else if (c.getMessage() != null && !c.getMessage().isEmpty()) {
            statusText.setText(c.getMessage());
        } else if (c.isBusy()) {
            statusText.setText(c.getStage());
        }
    }
}
