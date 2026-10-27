package com.offerbeacon.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * OfferBeacon —— 秋招投递记录工作台 · 移动端壳。
 *
 * 做法：把工作台 HTML 作为本地资产打包进 APK，通过虚拟域名 https://offerbeacon.local/
 * 交给 WebView 加载。使用 https 虚拟域名（而不是 file://）是为了让 localStorage 有稳定的
 * 同源 origin，数据在应用私有目录里长期保留，升级安装不会丢。
 */
public class MainActivity extends Activity {

    private static final String HOST = "offerbeacon.local";
    private static final String START_URL = "https://" + HOST + "/index.html";
    private static final String ENTRY_ASSET = "index.html";
    private static final String BG_COLOR = "#F4F5FB";
    private static final String BG_COLOR_DARK = "#0D1524";
    private static final String PREFS = "offerbeacon_prefs";
    private static final String KEY_THEME = "ui_theme";

    private WebView webView;
    private long lastBackPressedAt = 0L;
    /** 原生侧当前主题（跟随页内黑暗模式切换，决定 select 弹层 / 日期弹框配色） */
    private boolean darkTheme = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 恢复上次主题，避免冷启动时原生弹层与页内主题短暂不一致
        darkTheme = "dark".equals(
                getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_THEME, "light"));
        setTheme(darkTheme ? R.style.AppTheme_Dark : R.style.AppTheme);
        applySystemBars();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(currentBgColor());
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setBackgroundColor(currentBgColor());
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        configureWebView(webView);

        root.addView(webView);
        setContentView(root);

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        }
        if (webView.getUrl() == null) {
            webView.loadUrl(START_URL);
        }
    }

    private int currentBgColor() {
        return Color.parseColor(darkTheme ? BG_COLOR_DARK : BG_COLOR);
    }

    /** 系统栏颜色与前景图标随页内主题切换（浅色态保持原样） */
    private void applySystemBars() {
        int bg = currentBgColor();
        int nav = darkTheme ? bg : Color.WHITE;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(nav);
        int flags = 0;
        if (!darkTheme) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(flags);
    }

    /**
     * 页内黑暗模式切换 → 原生侧同步。
     * 运行时 setTheme 只影响之后弹出的原生对话框（select 弹层、日期/时间弹框），
     * 不影响已渲染的 WebView 内容；页面配色仍由 index.html 自身的 data-theme 管理。
     */
    private void applyThemeMode(String mode) {
        final boolean dark = "dark".equals(mode);
        if (dark == darkTheme) {
            return;
        }
        darkTheme = dark;
        setTheme(dark ? R.style.AppTheme_Dark : R.style.AppTheme);
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putString(KEY_THEME, dark ? "dark" : "light").apply();
        applySystemBars();
        if (webView != null) {
            webView.setBackgroundColor(currentBgColor());
        }
    }

    /** 页内主题桥（命名内部类，规避 d8 对匿名类的已知崩溃） */
    public class ThemeBridge {
        @JavascriptInterface
        public void setTheme(final String mode) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyThemeMode(mode);
                }
            });
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebView view) {
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setSaveFormData(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " OfferBeacon/1.0");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(true);
        }

        view.setWebViewClient(new LocalWebViewClient());

        // 页内主题切换桥：index.html 切换黑暗模式时调用 OfferBeaconTheme.setTheme(mode)
        view.addJavascriptInterface(new ThemeBridge(), "OfferBeaconTheme");

        view.setWebChromeClient(new LocalChromeClient());
    }

    /** 本地资产拦截 + 外链跳系统浏览器（命名内部类，规避 d8 对匿名类的已知崩溃） */
    private class LocalWebViewClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (uri != null && HOST.equals(uri.getHost())) {
                return serveAsset(uri);
            }
            return null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (uri != null && HOST.equals(uri.getHost())) {
                return false;
            }
            openExternally(uri);
            return true;
        }
    }

    /** 新窗口链接（如企业官网）交给系统浏览器 */
    private class LocalChromeClient extends WebChromeClient {
        @Override
        public boolean onCreateWindow(WebView v, boolean isDialog,
                                      boolean isUserGesture, android.os.Message resultMsg) {
            WebView.HitTestResult hit = v.getHitTestResult();
            if (hit != null && hit.getExtra() != null) {
                openExternally(Uri.parse(hit.getExtra()));
            }
            return false;
        }
    }

    /** 把 https://offerbeacon.local/xxx 映射到 assets/xxx */
    private WebResourceResponse serveAsset(Uri uri) {
        String path = uri.getPath();
        if (path == null || "/".equals(path) || path.isEmpty()) {
            path = ENTRY_ASSET;
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.contains("..")) {
            path = ENTRY_ASSET;
        }
        try {
            InputStream in = getAssets().open(path);
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Cache-Control", "no-cache");
            headers.put("Access-Control-Allow-Origin", "*");
            String mime = mimeOf(path);
            String encoding = mime.startsWith("text/") || mime.contains("json")
                    || mime.contains("javascript") || mime.contains("svg") ? "utf-8" : null;
            return new WebResourceResponse(mime, encoding, 200, "OK", headers, in);
        } catch (IOException e) {
            return null;
        }
    }

    private String mimeOf(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }

    private void openExternally(Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "没有可以打开该链接的应用", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPressedAt < 2000L) {
            super.onBackPressed();
        } else {
            lastBackPressedAt = now;
            Toast.makeText(this, "再按一次退出 OfferBeacon", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
